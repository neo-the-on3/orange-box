package io.github.neotheon3.orangebox.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okio.Buffer
import okio.GzipSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** MockWebServer contract tests: request shapes and response handling as docs/backend/openapi-v2.yaml defines them. */
class OkHttpBackendApiTest {
    private lateinit var server: MockWebServer
    private val sessions = InMemorySessionStore()
    private lateinit var api: OkHttpBackendApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = OkHttpBackendApi(server.url("/").toString(), OkHttpClient(), sessions, clock = { 1_000_000L }, allowCleartextForTests = true)
    }

    @After fun tearDown() = server.close()

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) = MockResponse.Builder()
        .code(code)
        .headers(headersOf("Content-Type", if (code >= 400) "application/problem+json" else "application/json", *headers.flatMap { listOf(it.first, it.second) }.toTypedArray()))
        .body(body)
        .build()

    private suspend fun signedIn(access: String = "a1") = sessions.write(Session(access, "r1", "m1", 2_000_000L, 9_000_000L, email = "k@example.com"))

    @Test fun `base url must be https`() {
        assertThrows(IllegalArgumentException::class.java) { OkHttpBackendApi("http://api.example.com", OkHttpClient(), sessions) }
        assertThrows(IllegalArgumentException::class.java) { OkHttpBackendApi("not a url", OkHttpClient(), sessions) }
        assertThrows(IllegalArgumentException::class.java) { OkHttpBackendApi("https://api.example.com/?x=1", OkHttpClient(), sessions) }
        OkHttpBackendApi("https://api.example.com/prefix", OkHttpClient(), sessions)
    }

    @Test fun `email otp sign-in stores the session and sends no auth header`() = runTest {
        server.enqueue(json(202, """{"expiresIn":600,"resendAfter":60}"""))
        server.enqueue(json(200, TOKEN_JSON))
        assertEquals(600, api.requestOtp(" Kiran@Example.com ").expiresIn)
        val tokens = api.signIn("kiran@example.com", "012345")
        assertEquals("m-1", tokens.memberId)

        val otp = server.takeRequest()
        assertEquals("/v1/auth/otp", otp.url.encodedPath)
        assertEquals("""{"email":"Kiran@Example.com"}""", otp.body!!.utf8())
        assertNull(otp.headers["Authorization"])
        assertNotNull(otp.headers["X-Request-Id"])
        val token = server.takeRequest()
        assertEquals("""{"email":"kiran@example.com","code":"012345","grantType":"email_otp"}""", token.body!!.utf8())

        val s = sessions.read()!!
        assertEquals("acc", s.accessToken)
        assertEquals("ref", s.refreshToken)
        assertEquals(1_000_000L + 900_000L, s.accessExpiresAtMillis)
        assertEquals("the normalised address is kept for Settings", "kiran@example.com", s.email)
    }

    @Test fun `bearer token is attached and the member id is never sent`() = runTest {
        signedIn()
        server.enqueue(json(200, """{"items":[],"nextCursor":null,"nextSince":"S1"}"""))
        api.listTrips()
        val r = server.takeRequest()
        assertEquals("Bearer a1", r.headers["Authorization"])
        assertFalse(r.url.toString().contains("m1"))
    }

    @Test fun `401 refreshes once and concurrent callers share the refresh`() = runTest {
        signedIn("old")
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/v1/auth/token" -> {
                    refreshes.incrementAndGet()
                    Thread.sleep(50)
                    json(200, TOKEN_JSON)
                }
                else -> if (request.headers["Authorization"] == "Bearer acc") json(200, """{"items":[],"nextCursor":null,"nextSince":"S"}""")
                else json(401, problem(401, "token_expired"))
            }
        }
        val pages = List(5) { async { api.listTrips() } }.awaitAll()
        assertEquals(5, pages.size)
        assertEquals(1, refreshes.get())
        assertEquals("acc", sessions.read()!!.accessToken)
        assertEquals("a refresh keeps the address", "k@example.com", sessions.read()!!.email)
    }

    @Test fun `rejected refresh clears the session`() = runTest {
        signedIn()
        server.enqueue(json(401, problem(401, "token_expired")))
        server.enqueue(json(400, problem(400, "invalid_grant")))
        try {
            api.listTrips()
            fail()
        } catch (e: ApiError.NotSignedIn) {
            // expected
        }
        assertNull(sessions.read())
        assertThrows(ApiError.NotSignedIn::class.java) { kotlinx.coroutines.runBlocking { api.listTrips() } }
    }

    @Test fun `problem+json maps to ApiError with code, reason and request id`() = runTest {
        signedIn()
        server.enqueue(json(400, """{"type":"about:blank","title":"Sync window expired","status":400,"code":"sync_window_expired","reason":"deployment_changed","requestId":"req-9"}"""))
        val e = runCatching { api.listTrips(since = "S") }.exceptionOrNull() as ApiError.Http
        assertTrue(e.isSyncWindowExpired)
        assertEquals(SyncWindowReason.DEPLOYMENT_CHANGED, e.syncWindowReason)
        assertEquals("req-9", e.requestId)
        assertFalse(e.isRetryable)
    }

    @Test fun `sync window reason is decoded leniently`() {
        assertEquals(SyncWindowReason.PURGED, SyncWindowReason.parse("purged"))
        assertEquals(SyncWindowReason.INVALID, SyncWindowReason.parse("invalid"))
        assertEquals(SyncWindowReason.DEPLOYMENT_CHANGED, SyncWindowReason.parse("deployment_changed"))
        assertEquals("unknown is invalid", SyncWindowReason.INVALID, SyncWindowReason.parse("cosmic_rays"))
        assertEquals("missing is invalid", SyncWindowReason.INVALID, SyncWindowReason.parse(null))
        val p = BackendJson.decodeFromString(ProblemDto.serializer(), """{"type":"about:blank","title":"x","status":400,"requestId":"r","code":"sync_window_expired","reason":42,"extra":{"a":1}}""")
        assertEquals(SyncWindowReason.INVALID, SyncWindowReason.parse(p.reason))
    }

    @Test fun `503 and 429 carry Retry-After and are retryable`() = runTest {
        signedIn()
        server.enqueue(json(503, problem(503, "service_unavailable"), "Retry-After" to "7"))
        val e = runCatching { api.listTrips() }.exceptionOrNull() as ApiError.Http
        assertEquals(7L, e.retryAfterSeconds)
        assertTrue(e.isRetryable)
        server.enqueue(json(429, problem(429, "rate_limited"), "Retry-After" to "3"))
        val r = runCatching { api.listTrips() }.exceptionOrNull() as ApiError.Http
        assertEquals(3L, r.retryAfterSeconds)
        assertTrue(r.isRetryable)
    }

    @Test fun `vehicle create - 201, 200 replay, 410 vehicle_deleted, 409 in flight`() = runTest {
        signedIn()
        val v = VehicleCreateDto(clientVehicleId = "3f0e1c7a-0000-4000-8000-000000000001", make = "Maruti Suzuki", model = "SX4", fuelType = "gasoline")
        server.enqueue(json(201, VEHICLE_JSON))
        server.enqueue(json(200, VEHICLE_JSON))
        server.enqueue(json(410, problem(410, "vehicle_deleted")))
        server.enqueue(json(409, problem(409, "idempotency_in_flight")))
        assertEquals("veh_1", api.createVehicle(v, "k1").id)
        assertEquals("veh_1", api.createVehicle(v, "k2").id)
        val gone = runCatching { api.createVehicle(v, "k3") }.exceptionOrNull() as ApiError.Http
        assertEquals(ApiError.CODE_VEHICLE_DELETED, gone.code)
        assertFalse(gone.isRetryable)
        val inFlight = runCatching { api.createVehicle(v, "k4") }.exceptionOrNull() as ApiError.Http
        assertTrue(inFlight.isRetryable)
        val first = server.takeRequest()
        assertEquals("k1", first.headers["Idempotency-Key"])
        assertTrue(first.body!!.utf8().contains("\"clientVehicleId\":\"3f0e1c7a-0000-4000-8000-000000000001\""))
    }

    @Test fun `vehicle list with archived ones, and a vehicle with only a nickname decodes`() = runTest {
        signedIn()
        server.enqueue(json(200, """{"items":[$VEHICLE_JSON,{"id":"veh_2","nickname":"Blue","fuelType":"other","archived":true,"createdAt":"2026-10-23T14:05:00Z","updatedAt":"2026-10-24T14:05:00Z"}]}"""))
        val page = api.listVehicles(includeArchived = true)
        assertEquals(listOf("veh_1", "veh_2"), page.items.map { it.id })
        assertEquals(null, page.items[1].make)
        assertEquals("Blue", page.items[1].nickname)
        assertEquals("true", server.takeRequest().url.queryParameter("includeArchived"))
    }

    @Test fun `trip walk - since only on the first page, cursor afterwards, opaque tokens untouched`() = runTest {
        signedIn()
        server.enqueue(json(200, """{"items":[$TRIP_JSON],"nextCursor":"c/2+=","nextSince":null}"""))
        server.enqueue(json(200, """{"items":[],"nextCursor":null,"nextSince":"S+/2=="}"""))
        val p1 = api.listTrips(since = "S+/1==")
        val p2 = api.listTrips(cursor = p1.nextCursor)
        assertEquals("S+/2==", p2.nextSince)
        val r1 = server.takeRequest()
        assertEquals("S+/1==", r1.url.queryParameter("since"))
        assertNull(r1.url.queryParameter("cursor"))
        val r2 = server.takeRequest()
        assertEquals("c/2+=", r2.url.queryParameter("cursor"))
        assertNull(r2.url.queryParameter("since"))
        val trip = p1.items.single()
        assertEquals(Instant.parse("2026-10-23T14:05:00.1Z"), trip.updatedAt)
    }

    @Test fun `deletions walk sends since on every page`() = runTest {
        signedIn()
        server.enqueue(json(200, """{"items":[{"id":"t1","clientTripId":"c1","deletedAt":"2026-10-23T14:05:00Z"}],"nextCursor":"d2","nextSince":null}"""))
        server.enqueue(json(200, """{"items":[],"nextCursor":null,"nextSince":"ignored"}"""))
        api.listTripDeletions("S")
        api.listTripDeletions("S", "d2")
        assertEquals("/v1/trips/deletions", server.takeRequest().url.encodedPath)
        val r2 = server.takeRequest()
        assertEquals("S", r2.url.queryParameter("since"))
        assertEquals("d2", r2.url.queryParameter("cursor"))
    }

    @Test fun `trip batch is gzip json with an idempotency key and per-item results`() = runTest {
        signedIn()
        server.enqueue(json(200, """{"results":[{"clientId":"c1","status":"created","id":"t1"},{"clientId":"c2","status":"rejected","id":null,"problem":{"type":"about:blank","title":"Deleted","status":410,"code":"trip_deleted","requestId":"r"}}]}"""))
        val upload = TripUploadDto(
            clientTripId = "c1", vehicleId = "veh_1", state = TripUploadDto.STATE_COMPLETED,
            startedAt = Instant.parse("2026-10-23T14:05:00Z"), snapshotAt = Instant.parse("2026-10-23T14:35:00.123Z"),
            endedAt = Instant.parse("2026-10-23T14:35:00Z"), distanceKm = 12.5,
        )
        val result = api.uploadTrips(TripBatchDto(listOf(upload, upload.copy(clientTripId = "c2"))), "batch-key")
        assertTrue(result.results[0].accepted)
        assertFalse(result.results[1].accepted)
        assertEquals(ApiError.CODE_TRIP_DELETED, result.results[1].problem!!.code)

        val r = server.takeRequest()
        assertEquals("gzip", r.headers["Content-Encoding"])
        assertEquals("batch-key", r.headers["Idempotency-Key"])
        val body = GzipSource(Buffer().write(r.body!!)).buffer().readUtf8()
        assertTrue(body, body.contains("\"startedAt\":\"2026-10-23T14:05:00Z\""))
        assertTrue(body, body.contains("\"snapshotAt\":\"2026-10-23T14:35:00.123Z\""))
        assertFalse("absent fields are omitted, not null", body.contains("null"))
    }

    private fun telemetryBatch(samples: Int = 2, events: Int = 1) = TelemetryBatchDto(
        clientBatchId = "b-1", clientTripId = "c1", vehicleId = "veh_1",
        samples = List(samples) { SampleDto(t = Instant.parse("2026-10-23T14:05:00.123Z").plusSeconds(it.toLong()), speedKmh = 42.0, rpm = 1800.0) },
        events = List(events) { EventDto(t = Instant.parse("2026-10-23T14:05:01Z"), type = "harsh_brake", clientEventId = "e-$it", value = -4.2) },
    )

    @Test fun `telemetry batch is gzip json with an idempotency key and the 202 ack`() = runTest {
        signedIn()
        server.enqueue(json(202, """{"clientBatchId":"b-1","status":"accepted","acceptedSamples":2,"acceptedEvents":1,"droppedSamples":0,"droppedEvents":0}"""))
        val ack = api.uploadTelemetry(telemetryBatch(), "tel-key")
        assertEquals(TelemetryBatchAckDto.STATUS_ACCEPTED, ack.status)
        assertEquals(2, ack.acceptedSamples)
        assertFalse(ack.tripDeleted)

        val r = server.takeRequest()
        assertEquals("/v1/telemetry/batches", r.url.encodedPath)
        assertEquals("gzip", r.headers["Content-Encoding"])
        assertEquals("tel-key", r.headers["Idempotency-Key"])
        val body = GzipSource(Buffer().write(r.body!!)).buffer().readUtf8()
        assertTrue(body, body.contains("\"t\":\"2026-10-23T14:05:00.123Z\""))
        assertTrue(body, body.contains("\"clientEventId\":\"e-0\""))
        assertTrue(body, body.contains("\"type\":\"harsh_brake\""))
        assertFalse("absent fields are omitted, not null", body.contains("null"))
    }

    @Test fun `telemetry ack for a deleted trip, and 413 or 404 are errors`() = runTest {
        signedIn()
        server.enqueue(json(202, """{"clientBatchId":"b-1","status":"trip_deleted","acceptedSamples":0,"acceptedEvents":0}"""))
        assertTrue(api.uploadTelemetry(telemetryBatch(), "k").tripDeleted)
        server.enqueue(json(413, problem(413, "batch_too_large")))
        val tooLarge = runCatching { api.uploadTelemetry(telemetryBatch(), "k") }.exceptionOrNull() as ApiError.Http
        assertEquals(413, tooLarge.status)
        assertFalse(tooLarge.isRetryable)
        server.enqueue(json(404, problem(404, ApiError.CODE_VEHICLE_NOT_FOUND)))
        val missing = runCatching { api.uploadTelemetry(telemetryBatch(), "k") }.exceptionOrNull() as ApiError.Http
        assertEquals(ApiError.CODE_VEHICLE_NOT_FOUND, missing.code)
    }

    @Test fun `telemetry batch limits are checked before sending`() = runTest {
        signedIn()
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { api.uploadTelemetry(telemetryBatch(samples = 5_001), "k") } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { api.uploadTelemetry(telemetryBatch(events = 501), "k") } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { api.uploadTelemetry(telemetryBatch(0, 0), "k") } }
        assertEquals(0, server.requestCount)
    }

    @Test fun `dtc report - 201, 200 replay, 404 unknown vehicle`() = runTest {
        signedIn()
        val report = DtcReportCreateDto(
            clientReportId = "5b1d9f3e-0000-4000-8000-000000000001", kind = DtcReportCreateDto.KIND_READ,
            readAt = Instant.parse("2026-10-23T14:05:00Z"), mil = true, stored = listOf("P0300"), pending = emptyList(),
        )
        val created = """{"id":"dtc_1","vehicleId":"veh_1","createdAt":"2026-10-23T14:05:02Z","clientReportId":"${report.clientReportId}",""" +
            """"kind":"read","readAt":"2026-10-23T14:05:00Z","mil":true,"stored":["P0300"],"pending":[]}"""
        server.enqueue(json(201, created))
        server.enqueue(json(200, created))
        server.enqueue(json(404, problem(404, ApiError.CODE_VEHICLE_NOT_FOUND)))
        assertEquals("dtc_1", api.createDtcReport("veh_1", report, report.clientReportId).id)
        assertEquals("dtc_1", api.createDtcReport("veh_1", report, report.clientReportId).id)
        val missing = runCatching { api.createDtcReport("veh_9", report, report.clientReportId) }.exceptionOrNull() as ApiError.Http
        assertEquals(ApiError.CODE_VEHICLE_NOT_FOUND, missing.code)
        assertFalse(missing.isRetryable)

        val r = server.takeRequest()
        assertEquals("/v1/vehicles/veh_1/dtc-reports", r.url.encodedPath)
        assertEquals(report.clientReportId, r.headers["Idempotency-Key"])
        assertEquals(null, r.headers["Content-Encoding"])
        val body = r.body!!.utf8()
        assertTrue(body, body.contains("\"kind\":\"read\"") && body.contains("\"stored\":[\"P0300\"]") && body.contains("\"mil\":true"))
        assertFalse("absent fields are omitted, not null", body.contains("null"))
    }

    @Test fun `deleting an already deleted trip counts as done`() = runTest {
        signedIn()
        server.enqueue(json(404, problem(404, "not_found")))
        api.deleteTrip("t1")
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun `logout clears the session even when the call fails`() = runTest {
        signedIn()
        server.enqueue(json(500, problem(500, "internal_error")))
        api.logout()
        assertNull(sessions.read())
    }

    @Test fun `each request gets its own X-Request-Id`() = runTest {
        server.enqueue(json(200, HEALTH_JSON))
        server.enqueue(json(200, HEALTH_JSON))
        api.health()
        api.health()
        val ids = ConcurrentLinkedQueue<String>()
        repeat(2) { ids.add(server.takeRequest().headers["X-Request-Id"]!!) }
        assertEquals(2, ids.toSet().size)
    }

    companion object {
        fun problem(status: Int, code: String) = """{"type":"about:blank","title":"t","status":$status,"code":"$code","requestId":"req"}"""

        const val TOKEN_JSON = """{"accessToken":"acc","tokenType":"Bearer","expiresIn":900,"refreshToken":"ref","refreshExpiresIn":2592000,"memberId":"m-1","isNewMember":true}"""
        const val HEALTH_JSON = """{"status":"ok","apiVersion":"1.0.0","time":"2026-10-23T14:05:00Z"}"""
        const val VEHICLE_JSON = """{"id":"veh_1","clientVehicleId":"3f0e1c7a-0000-4000-8000-000000000001","make":"Maruti Suzuki","model":"SX4","fuelType":"gasoline","archived":false,"createdAt":"2026-10-23T14:05:00.000Z","updatedAt":"2026-10-23T14:05:00.000Z","futureField":true}"""
        const val TRIP_JSON = """{"id":"t1","clientTripId":"c1","vehicleId":"veh_1","state":"completed","startedAt":"2026-10-23T14:05:00.000Z","endedAt":"2026-10-23T14:35:00.000Z","snapshotAt":"2026-10-23T14:35:00.000Z","createdAt":"2026-10-23T14:05:00.000Z","updatedAt":"2026-10-23T14:05:00.1Z","distanceKm":12.5,"pointsAwarded":null}"""
    }
}
