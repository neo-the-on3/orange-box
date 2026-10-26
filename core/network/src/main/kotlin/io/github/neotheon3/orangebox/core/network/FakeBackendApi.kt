package io.github.neotheon3.orangebox.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * In-process REST v1 backend for Fake mode and tests: no network. Same DTOs and the same sync semantics as the
 * spec (Conventions → Incremental sync), simplified to one member:
 *
 * - every write gets the next sequence number ("writer transaction id"); a walk's watermark is the sequence
 *   at its first page, `since` returns writes at or above it (inclusive, at least once);
 * - tokens and cursors carry the deployment id: [restoreFromBackup] changes it, so old ones get
 *   `sync_window_expired` / `deployment_changed`, and it rolls the data back to a snapshot;
 * - [purgeTombstones] drops tombstones and records the purge boundary (`reason=purged`);
 * - trips and vehicles are keyed by client id; tombstones make `POST /v1/vehicles` answer `410` and batch items
 *   `rejected` / `trip_deleted`.
 *
 * [failNext] queues errors for the next calls (`503`, `409`, anything), [pageSize] makes walks multi-page.
 */
class FakeBackendApi(
    private val clock: () -> Instant = Instant::now,
    var pageSize: Int = 50,
    /** Fake mode accepts this code for any address. */
    val acceptedOtpCode: String = "000000",
) : BackendApi {

    data class StoredTrip(val dto: TripDto, val seq: Long)
    data class Tombstone(val id: String, val clientTripId: String, val vehicleId: String?, val deletedAt: Instant, val seq: Long)
    private data class State(
        val vehicles: Map<String, VehicleDto> = emptyMap(), // by clientVehicleId
        val vehicleTombstones: Set<String> = emptySet(),
        val trips: Map<String, StoredTrip> = emptyMap(), // by clientTripId
        val tombstones: Map<String, Tombstone> = emptyMap(), // by clientTripId
        val seq: Long = 0,
    )

    private val lock = Mutex()
    private var state = State()
    private var deployment = "d1"
    private var purgeBoundary: Long = 0
    private val failures = ArrayDeque<ApiError>()
    private var nextId = 0

    /** Every call made, for tests: `"GET /v1/trips?since=…"`-like strings. */
    val calls = mutableListOf<String>()
    var signedIn = false
        private set

    fun failNext(vararg errors: ApiError) {
        failures.addAll(errors)
    }

    val trips: List<TripDto> get() = state.trips.values.map { it.dto }
    val vehicles: List<VehicleDto> get() = state.vehicles.values.toList()
    val tripTombstones: Set<String> get() = state.tombstones.keys

    /** Simulates "deleted on another device". */
    suspend fun deleteTripElsewhere(clientTripId: String) = lock.withLock { deleteLocked(clientTripId) }

    suspend fun deleteVehicleElsewhere(clientVehicleId: String) = lock.withLock {
        val v = state.vehicles[clientVehicleId] ?: return@withLock
        state.trips.values.filter { it.dto.vehicleId == v.id }.forEach { deleteLocked(it.dto.clientTripId) }
        state = state.copy(vehicles = state.vehicles - clientVehicleId, vehicleTombstones = state.vehicleTombstones + clientVehicleId)
    }

    /** Snapshot to restore later with [restoreFromBackup]. */
    fun snapshot(): Any = state

    /** Database restore: data rolls back to [snapshot] and the deployment identity changes. */
    fun restoreFromBackup(snapshot: Any) {
        @Suppress("UNCHECKED_CAST")
        state = (snapshot as State).let { it.copy(seq = maxOf(it.seq, state.seq) + 1000) }
        deployment = "d" + (deployment.drop(1).toInt() + 1)
    }

    /** Purges every tombstone; any token from before now gets `reason=purged`. */
    fun purgeTombstones() {
        purgeBoundary = state.seq + 1
        state = state.copy(tombstones = emptyMap(), seq = state.seq + 1)
    }

    /** Seeds a trip "uploaded from another device". */
    suspend fun seedTrip(dto: TripUploadDto) = lock.withLock { upsertLocked(dto) }

    // --- BackendApi ---

    override suspend fun health() = op("GET /v1/health") { HealthDto("ok", "1.0.0", clock()) }

    override suspend fun requestOtp(email: String) = op("POST /v1/auth/otp") { OtpResponse(expiresIn = 600, resendAfter = 60) }

    override suspend fun signIn(email: String, code: String, device: DeviceInfo?) = op("POST /v1/auth/token") {
        if (code.trim() != acceptedOtpCode) throw http(400, "invalid_grant")
        signedIn = true
        TokenResponse("fake-access", "Bearer", 900, "fake-refresh", 2_592_000, "fake-member", isNewMember = false)
    }

    override suspend fun logout() = op("POST /v1/auth/logout") { signedIn = false }

    override suspend fun listVehicles(cursor: String?) = op("GET /v1/vehicles") {
        VehiclePage(state.vehicles.values.sortedBy { it.id }, nextCursor = null)
    }

    override suspend fun createVehicle(vehicle: VehicleCreateDto, idempotencyKey: String) = op("POST /v1/vehicles") {
        if (vehicle.clientVehicleId in state.vehicleTombstones) throw http(410, ApiError.CODE_VEHICLE_DELETED)
        state.vehicles[vehicle.clientVehicleId] ?: run {
            val now = clock()
            VehicleDto(
                id = "veh_" + (++nextId), make = vehicle.make, model = vehicle.model, fuelType = vehicle.fuelType,
                createdAt = now, updatedAt = now, clientVehicleId = vehicle.clientVehicleId, nickname = vehicle.nickname,
                engineDisplacementCc = vehicle.engineDisplacementCc, odometerKm = vehicle.odometerKm,
            ).also { state = state.copy(vehicles = state.vehicles + (vehicle.clientVehicleId to it), seq = state.seq + 1) }
        }
    }

    override suspend fun listTrips(since: String?, cursor: String?) = op("GET /v1/trips?since=$since&cursor=$cursor") {
        val walk = if (cursor != null) decodeCursor(cursor, "trips") else Walk("trips", sinceSeq(since), state.seq + 1, 0, since != null)
        val items = state.trips.values.filter { it.seq >= walk.from && it.seq < walk.watermark }.sortedWith(compareBy({ it.seq }, { it.dto.id }))
        val page = items.drop(walk.offset).take(pageSize)
        val more = walk.offset + page.size < items.size
        TripPage(
            items = page.map { it.dto },
            nextCursor = if (more) encodeCursor(walk.copy(offset = walk.offset + page.size)) else null,
            nextSince = if (more) null else token(walk.watermark),
        )
    }

    override suspend fun listTripDeletions(since: String, cursor: String?) = op("GET /v1/trips/deletions?since=$since&cursor=$cursor") {
        val walk = if (cursor != null) decodeCursor(cursor, "deletions") else Walk("deletions", sinceSeq(since), state.seq + 1, 0, true)
        val items = state.tombstones.values.filter { it.seq >= walk.from && it.seq < walk.watermark }.sortedBy { it.seq }
        val page = items.drop(walk.offset).take(pageSize)
        val more = walk.offset + page.size < items.size
        TripDeletionPage(
            items = page.map { TripDeletionDto(it.id, it.clientTripId, it.deletedAt, it.vehicleId) },
            nextCursor = if (more) encodeCursor(walk.copy(offset = walk.offset + page.size)) else null,
            nextSince = if (more) null else token(walk.watermark),
        )
    }

    override suspend fun uploadTrips(batch: TripBatchDto, idempotencyKey: String) = op("POST /v1/trips/batch") {
        BatchResultDto(
            batch.trips.map { t ->
                when {
                    t.clientTripId in state.tombstones -> BatchItemResult(t.clientTripId, "rejected", null, problem(410, ApiError.CODE_TRIP_DELETED))
                    state.vehicles.values.none { it.id == t.vehicleId } ->
                        BatchItemResult(t.clientTripId, "rejected", null, problem(422, ApiError.CODE_VEHICLE_NOT_FOUND))
                    else -> {
                        val (status, dto) = upsertLocked(t)
                        BatchItemResult(t.clientTripId, status, dto.id)
                    }
                }
            },
        )
    }

    /** Telemetry stored per `clientTripId`, de-duplicated like the spec says (samples by `t`, events by id or key). */
    data class StoredTelemetry(val samples: Map<Instant, SampleDto> = emptyMap(), val events: List<EventDto> = emptyList())

    private val telemetryByTrip = mutableMapOf<String, StoredTelemetry>()
    private val telemetryBatches = mutableMapOf<String, TelemetryBatchAckDto>()

    val telemetry: Map<String, StoredTelemetry> get() = telemetryByTrip.toMap()

    /** Batches larger than this many samples + events answer `413` (to test splitting). */
    var maxTelemetryItems: Int = BackendApi.MAX_SAMPLES_PER_BATCH + BackendApi.MAX_EVENTS_PER_BATCH

    override suspend fun uploadTelemetry(batch: TelemetryBatchDto, idempotencyKey: String) = op("POST /v1/telemetry/batches") {
        telemetryBatches[batch.clientBatchId]?.let { return@op it.copy(status = TelemetryBatchAckDto.STATUS_DUPLICATE) }
        if (batch.samples.size + batch.events.size > maxTelemetryItems) throw http(413, "payload_too_large")
        if (batch.clientTripId in state.tombstones) {
            return@op TelemetryBatchAckDto(batch.clientBatchId, TelemetryBatchAckDto.STATUS_TRIP_DELETED, 0, 0)
        }
        if (state.vehicles.values.none { it.id == batch.vehicleId }) throw http(404, ApiError.CODE_VEHICLE_NOT_FOUND)
        val stored = telemetryByTrip[batch.clientTripId] ?: StoredTelemetry()
        val samples = batch.samples.distinctBy { it.t.toEpochMilli() }
        fun EventDto.key() = listOf(t.toEpochMilli(), type, active ?: true, dtc)
        val events = batch.events.distinctBy { it.key() }.filterNot { e ->
            stored.events.any { s -> (e.clientEventId != null && s.clientEventId == e.clientEventId) || s.key() == e.key() }
        }
        telemetryByTrip[batch.clientTripId] = StoredTelemetry(stored.samples + samples.associateBy { it.t }, stored.events + events)
        TelemetryBatchAckDto(
            batch.clientBatchId, TelemetryBatchAckDto.STATUS_ACCEPTED, samples.size, events.size,
            droppedSamples = batch.samples.size - samples.size, droppedEvents = batch.events.size - batch.events.distinctBy { it.key() }.size,
        ).also { telemetryBatches[batch.clientBatchId] = it }
    }

    override suspend fun deleteTrip(serverId: String) = op("DELETE /v1/trips/$serverId") {
        val trip = state.trips.values.firstOrNull { it.dto.id == serverId } ?: return@op // 404 counts as done
        deleteLocked(trip.dto.clientTripId)
    }

    // --- internals ---

    private data class Walk(val endpoint: String, val from: Long, val watermark: Long, val offset: Int, val incremental: Boolean)

    private suspend fun <T> op(name: String, block: () -> T): T = lock.withLock {
        calls += name
        failures.removeFirstOrNull()?.let { throw it }
        block()
    }

    private fun upsertLocked(t: TripUploadDto): Pair<String, TripDto> {
        val existing = state.trips[t.clientTripId]
        if (existing != null && (existing.dto.state == TripUploadDto.STATE_COMPLETED || !t.snapshotAt.isAfter(existing.dto.snapshotAt))) {
            return "unchanged" to existing.dto
        }
        val now = clock()
        val dto = TripDto(
            id = existing?.dto?.id ?: ("trip_" + (++nextId)), clientTripId = t.clientTripId, vehicleId = t.vehicleId,
            state = t.state, startedAt = t.startedAt, snapshotAt = t.snapshotAt, createdAt = existing?.dto?.createdAt ?: now,
            updatedAt = now, endedAt = t.endedAt, durationS = t.durationS, distanceKm = t.distanceKm, maxSpeedKmh = t.maxSpeedKmh,
            avgSpeedKmh = t.avgSpeedKmh, harshAccelCount = t.harshAccelCount, harshBrakeCount = t.harshBrakeCount,
            fuelUsedL = t.fuelUsedL, fuelCutTimeS = t.fuelCutTimeS, idleTimeS = t.idleTimeS, ecoSpeedTimeS = t.ecoSpeedTimeS,
            overSpeedTimeS = t.overSpeedTimeS, simulated = t.simulated,
        )
        val seq = state.seq + 1
        state = state.copy(trips = state.trips + (t.clientTripId to StoredTrip(dto, seq)), seq = seq)
        return (if (existing == null) "created" else "updated") to dto
    }

    private fun deleteLocked(clientTripId: String) {
        val trip = state.trips[clientTripId] ?: return
        val seq = state.seq + 1
        state = state.copy(
            trips = state.trips - clientTripId,
            tombstones = state.tombstones + (clientTripId to Tombstone(trip.dto.id, clientTripId, trip.dto.vehicleId, clock(), seq)),
            seq = seq,
        )
    }

    private fun token(watermark: Long) = b64("v1|$deployment|$watermark")

    private fun sinceSeq(since: String?): Long {
        if (since == null) return 0
        val parts = unb64(since)?.split('|')
        if (parts == null || parts.size != 3 || parts[0] != "v1") throw expired(SyncWindowReason.INVALID)
        if (parts[1] != deployment) throw expired(SyncWindowReason.DEPLOYMENT_CHANGED)
        val wm = parts[2].toLongOrNull() ?: throw expired(SyncWindowReason.INVALID)
        if (wm < purgeBoundary) throw expired(SyncWindowReason.PURGED)
        return wm
    }

    private fun encodeCursor(w: Walk) = b64("c|$deployment|${w.endpoint}|${w.from}|${w.watermark}|${w.offset}|${w.incremental}")

    private fun decodeCursor(cursor: String, endpoint: String): Walk {
        val p = unb64(cursor)?.split('|')
        if (p == null || p.size != 7 || p[0] != "c" || p[2] != endpoint) throw expired(SyncWindowReason.INVALID)
        if (p[1] != deployment) throw expired(SyncWindowReason.DEPLOYMENT_CHANGED)
        val w = Walk(endpoint, p[3].toLong(), p[4].toLong(), p[5].toInt(), p[6].toBoolean())
        if (w.incremental && w.from < purgeBoundary) throw expired(SyncWindowReason.PURGED)
        return w
    }

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun unb64(s: String) = runCatching { String(Base64.getUrlDecoder().decode(s)) }.getOrNull()

    private fun problem(status: Int, code: String, reason: String? = null) = ProblemDto(
        type = "about:blank", title = code, status = status, code = code, requestId = UUID.randomUUID().toString(),
        rawReason = reason?.let { kotlinx.serialization.json.JsonPrimitive(it) },
    )

    private fun http(status: Int, code: String) = ApiError.Http(status, problem(status, code))

    private fun expired(reason: SyncWindowReason) = ApiError.Http(400, problem(400, ApiError.CODE_SYNC_WINDOW_EXPIRED, reason.wire))

    companion object {
        fun syncWindowExpired(reason: String?) = ApiError.Http(
            400,
            ProblemDto("about:blank", "Sync window expired", 400, code = ApiError.CODE_SYNC_WINDOW_EXPIRED, requestId = "r",
                rawReason = reason?.let { kotlinx.serialization.json.JsonPrimitive(it) }),
        )

        fun unavailable(retryAfterSeconds: Long = 5) =
            ApiError.Http(503, ProblemDto("about:blank", "Unavailable", 503, code = "service_unavailable", requestId = "r"), retryAfterSeconds)
    }
}
