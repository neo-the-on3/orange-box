package io.github.neotheon3.orangebox.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.GzipSink
import okio.buffer
import java.io.IOException
import java.util.UUID

/**
 * REST v1 over OkHttp (docs/backend/openapi-v2.yaml).
 *
 * - [baseUrl] must be `https://` ([allowCleartextForTests] exists only for MockWebServer); `/v1` is appended.
 * - Every request carries a fresh `X-Request-Id`. Batch uploads are gzip-compressed and carry the caller's
 *   stable `Idempotency-Key` (reused on retries; that is the queue's job).
 * - Authenticated calls send `Authorization: Bearer`. On `401` the client refreshes once (`grantType=refresh_token`)
 *   and retries; concurrent callers share one refresh. A rejected refresh clears the session and throws
 *   [ApiError.NotSignedIn]. The member id is never sent in a body or query.
 */
class OkHttpBackendApi(
    baseUrl: String,
    private val client: OkHttpClient,
    private val sessions: SessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestId: () -> String = { UUID.randomUUID().toString() },
    allowCleartextForTests: Boolean = false,
) : BackendApi {

    private val root: HttpUrl = parseBaseUrl(baseUrl, allowCleartextForTests)
    private val refreshLock = Mutex()

    override suspend fun health(): HealthDto = call(get("health"), authenticated = false)

    override suspend fun requestOtp(email: String): OtpResponse =
        call(post("auth/otp", json(OtpRequest(email.trim()))), authenticated = false)

    override suspend fun signIn(email: String, code: String, device: DeviceInfo?): TokenResponse {
        val tokens: TokenResponse = call(post("auth/token", json(EmailOtpGrant(email.trim(), code.trim(), device))), authenticated = false)
        sessions.write(tokens.toSession(clock()))
        return tokens
    }

    override suspend fun logout() {
        val session = sessions.read() ?: return
        try {
            callUnit(post("auth/logout", json(RefreshGrant(session.refreshToken))), authenticated = true)
        } catch (e: ApiError) {
            // Signing out locally must work offline or with a dead session; the refresh token expires on its own.
        } finally {
            sessions.clear()
        }
    }

    override suspend fun listVehicles(cursor: String?): VehiclePage =
        call(get("vehicles") { cursor?.let { addQueryParameter("cursor", it) } }, authenticated = true)

    override suspend fun createVehicle(vehicle: VehicleCreateDto, idempotencyKey: String): VehicleDto =
        call(post("vehicles", json(vehicle)).idempotent(idempotencyKey), authenticated = true)

    override suspend fun listTrips(since: String?, cursor: String?): TripPage = call(
        get("trips") {
            since?.let { addQueryParameter("since", it) }
            cursor?.let { addQueryParameter("cursor", it) }
        },
        authenticated = true,
    )

    override suspend fun listTripDeletions(since: String, cursor: String?): TripDeletionPage = call(
        get("trips/deletions") {
            addQueryParameter("since", since) // required on every page; the walk's original value
            cursor?.let { addQueryParameter("cursor", it) }
        },
        authenticated = true,
    )

    override suspend fun uploadTrips(batch: TripBatchDto, idempotencyKey: String): BatchResultDto {
        require(batch.trips.size in 1..BackendApi.MAX_TRIPS_PER_BATCH) { "1..100 trips per batch" }
        return call(post("trips/batch", gzip(json(batch))).header("Content-Encoding", "gzip").idempotent(idempotencyKey), authenticated = true)
    }

    override suspend fun uploadTelemetry(batch: TelemetryBatchDto, idempotencyKey: String): TelemetryBatchAckDto {
        require(batch.samples.size <= BackendApi.MAX_SAMPLES_PER_BATCH && batch.events.size <= BackendApi.MAX_EVENTS_PER_BATCH) {
            "at most 5,000 samples and 500 events per batch"
        }
        require(batch.samples.isNotEmpty() || batch.events.isNotEmpty()) { "empty telemetry batch" }
        return call(post("telemetry/batches", gzip(json(batch))).header("Content-Encoding", "gzip").idempotent(idempotencyKey), authenticated = true)
    }

    override suspend fun deleteTrip(serverId: String) {
        try {
            callUnit(Request.Builder().url(url("trips/$serverId")).delete(), authenticated = true)
        } catch (e: ApiError.Http) {
            if (e.status != 404) throw e
        }
    }

    // --- plumbing ---

    private suspend inline fun <reified T> call(builder: Request.Builder, authenticated: Boolean): T =
        decode(serializer<T>(), execute(builder, authenticated))

    private suspend fun callUnit(builder: Request.Builder, authenticated: Boolean) {
        execute(builder, authenticated)
    }

    private fun <T> decode(serializer: KSerializer<T>, body: String): T = try {
        BackendJson.decodeFromString(serializer, body)
    } catch (e: IllegalArgumentException) {
        throw ApiError.Network(IOException("unexpected response body", e))
    }

    /** Runs the request (refreshing once on 401) and returns the 2xx body text. */
    private suspend fun execute(builder: Request.Builder, authenticated: Boolean): String {
        builder.header("X-Request-Id", requestId()).header("Accept", "application/json")
        if (!authenticated) return send(builder.build()).use { it.bodyOrThrow() }

        val session = sessions.read() ?: throw ApiError.NotSignedIn()
        send(builder.bearer(session.accessToken).build()).use { first ->
            if (first.code != 401) return first.bodyOrThrow()
        }
        val fresh = refresh(staleAccessToken = session.accessToken)
        return send(builder.bearer(fresh.accessToken).build()).use { it.bodyOrThrow() }
    }

    /** Single-flight: whoever gets the lock first refreshes; the others see the new token and reuse it. */
    private suspend fun refresh(staleAccessToken: String): Session = refreshLock.withLock {
        val current = sessions.read() ?: throw ApiError.NotSignedIn()
        if (current.accessToken != staleAccessToken) return current
        val request = post("auth/token", json(RefreshGrant(current.refreshToken)))
            .header("X-Request-Id", requestId()).header("Accept", "application/json").build()
        val tokens = send(request).use { response ->
            if (response.code == 400 || response.code == 401) {
                sessions.clear()
                throw ApiError.NotSignedIn()
            }
            decode(TokenResponse.serializer(), response.bodyOrThrow())
        }
        tokens.toSession(clock()).also { sessions.write(it) }
    }

    private suspend fun send(request: Request): Response = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }
    }

    private fun Response.bodyOrThrow(): String {
        val text = try {
            body.string()
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }
        if (isSuccessful) return text
        val problem = runCatching { BackendJson.decodeFromString(ProblemDto.serializer(), text) }.getOrNull()
        throw ApiError.Http(
            status = code,
            problem = problem,
            retryAfterSeconds = header("Retry-After")?.trim()?.toLongOrNull(),
            requestId = header("X-Request-Id") ?: problem?.requestId,
        )
    }

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        root.newBuilder().addPathSegments("v1/$path").apply(query).build()

    private fun get(path: String, query: HttpUrl.Builder.() -> Unit = {}) = Request.Builder().url(url(path, query)).get()

    private fun post(path: String, body: RequestBody) = Request.Builder().url(url(path)).post(body)

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    private fun Request.Builder.idempotent(key: String) = header("Idempotency-Key", key)

    private inline fun <reified T> json(value: T): RequestBody =
        BackendJson.encodeToString(serializer<T>(), value).toRequestBody(JSON)

    private fun gzip(body: RequestBody): RequestBody {
        val buffer = Buffer()
        GzipSink(buffer).buffer().use { body.writeTo(it) }
        val bytes = buffer.readByteArray()
        return object : RequestBody() {
            override fun contentType() = JSON
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                sink.write(bytes)
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Accepts `https://host[:port][/prefix]` only (or `http://` when [allowCleartext]), without query or fragment. */
        fun parseBaseUrl(raw: String, allowCleartext: Boolean = false): HttpUrl {
            val url = raw.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("not a URL")
            require(url.isHttps || allowCleartext) { "the backend base URL must be https" }
            require(url.query == null && url.fragment == null) { "no query or fragment in the base URL" }
            return url
        }
    }
}
