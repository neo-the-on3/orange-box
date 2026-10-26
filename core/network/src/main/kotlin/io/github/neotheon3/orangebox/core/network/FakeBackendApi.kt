package io.github.neotheon3.orangebox.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
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

    override suspend fun listVehicles(cursor: String?, includeArchived: Boolean) = op("GET /v1/vehicles?includeArchived=$includeArchived") {
        VehiclePage(state.vehicles.values.filter { includeArchived || !it.archived }.sortedBy { it.id }, nextCursor = null)
    }

    /** Another device edits (or archives) the vehicle: a newer `updatedAt`. */
    suspend fun editVehicleElsewhere(clientVehicleId: String, edit: (VehicleDto) -> VehicleDto) = lock.withLock {
        val v = state.vehicles.getValue(clientVehicleId)
        state = state.copy(vehicles = state.vehicles + (clientVehicleId to edit(v).copy(updatedAt = clock())))
    }

    override suspend fun createVehicle(vehicle: VehicleCreateDto, idempotencyKey: String) = op("POST /v1/vehicles") {
        // Input is strict (draft.9): an unknown fuel type, or "" for make / model, is a 422.
        if (vehicle.fuelType !in FuelTypes.ALL || vehicle.make == "" || vehicle.model == "") throw http(422, ApiError.CODE_VALIDATION_FAILED)
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

    /** `PATCH` requests that answer with these first (e.g. `412` for a stale ETag), for tests. */
    val patchFailures = ArrayDeque<ApiError>()
    val patches = mutableListOf<Pair<String, VehiclePatchDto>>()

    private fun etagOf(v: VehicleDto) = "\"" + v.updatedAt.toEpochMilli() + "\""

    override suspend fun getVehicle(serverId: String) = op("GET /v1/vehicles/$serverId") {
        val v = state.vehicles.values.firstOrNull { it.id == serverId } ?: throw http(404, "not_found")
        Tagged(v, etagOf(v))
    }

    override suspend fun patchVehicle(serverId: String, patch: VehiclePatchDto, etag: String) = op("PATCH /v1/vehicles/$serverId") {
        patchFailures.removeFirstOrNull()?.let { throw it }
        val v = state.vehicles.values.firstOrNull { it.id == serverId } ?: throw http(404, "not_found")
        if (etag.isBlank()) throw http(428, ApiError.CODE_PRECONDITION_REQUIRED)
        if (etag != etagOf(v)) throw http(412, ApiError.CODE_ETAG_MISMATCH)
        if (patch.fuelType != null && patch.fuelType !in FuelTypes.ALL) throw http(422, ApiError.CODE_VALIDATION_FAILED)
        patches += serverId to patch
        val updated = v.copy(fuelType = patch.fuelType ?: v.fuelType, nickname = patch.nickname ?: v.nickname, updatedAt = clock())
        state = state.copy(vehicles = state.vehicles + (v.clientVehicleId!! to updated))
        Tagged(updated, etagOf(updated))
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

    /** Trips encoded larger than this fail the whole batch with `422` at `/trips/<i>` (spec: 16 KiB). */
    var maxTripBytes: Int = TripUploadDto.MAX_TRIP_BYTES

    override suspend fun uploadTrips(batch: TripBatchDto, idempotencyKey: String) = op("POST /v1/trips/batch") {
        val tooLarge = batch.trips.withIndex().filter { it.value.encodedBytes() > maxTripBytes }
        if (tooLarge.isNotEmpty()) {
            throw ApiError.Http(
                422,
                problem(422, ApiError.CODE_VALIDATION_FAILED).copy(errors = tooLarge.map { ProblemDto.FieldError("/trips/${it.index}", "trip too large") }),
            )
        }
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

    private val telemetryFailures = ArrayDeque<ApiError>()

    /** Like [failNext], but only for the next telemetry uploads. */
    fun failNextTelemetry(vararg errors: ApiError) {
        telemetryFailures.addAll(errors)
    }

    /** (Idempotency-Key, clientBatchId) of every telemetry request, failed ones included. */
    val telemetryRequests = mutableListOf<Pair<String, String>>()

    override suspend fun uploadTelemetry(batch: TelemetryBatchDto, idempotencyKey: String): TelemetryBatchAckDto {
        telemetryRequests += idempotencyKey to batch.clientBatchId
        telemetryFailures.removeFirstOrNull()?.let { calls += "POST /v1/telemetry/batches"; throw it }
        return storeTelemetry(batch)
    }

    private suspend fun storeTelemetry(batch: TelemetryBatchDto) = op("POST /v1/telemetry/batches") {
        telemetryBatches[batch.clientBatchId]?.let { return@op it.copy(status = TelemetryBatchAckDto.STATUS_DUPLICATE) }
        if (batch.samples.size > BackendApi.MAX_SAMPLES_PER_BATCH || batch.events.size > BackendApi.MAX_EVENTS_PER_BATCH) {
            throw http(422, ApiError.CODE_VALIDATION_FAILED) // over the count: not retried
        }
        if (batch.samples.size + batch.events.size > maxTelemetryItems) throw http(413, ApiError.CODE_BATCH_TOO_LARGE)
        if (batch.clientTripId in state.tombstones) {
            return@op TelemetryBatchAckDto(batch.clientBatchId, TelemetryBatchAckDto.STATUS_TRIP_DELETED, 0, 0)
        }
        if (state.vehicles.values.none { it.id == batch.vehicleId }) throw http(404, ApiError.CODE_VEHICLE_NOT_FOUND)
        val stored = telemetryByTrip[batch.clientTripId] ?: StoredTelemetry()
        val samples = batch.samples.distinctBy { it.t.toEpochMilli() }
        // Spec: an event is identified by its clientEventId, or without one by (clientTripId, t, type, active, dtc).
        fun EventDto.identity(): Any = clientEventId ?: listOf(batch.clientTripId, t.toEpochMilli(), type, active ?: true, dtc)
        val seen = stored.events.map { it.identity() }.toSet()
        val inBatch = batch.events.distinctBy { it.identity() }
        val events = inBatch.filterNot { it.identity() in seen }
        telemetryByTrip[batch.clientTripId] = StoredTelemetry(stored.samples + samples.associateBy { it.t }, stored.events + events)
        TelemetryBatchAckDto(
            batch.clientBatchId, TelemetryBatchAckDto.STATUS_ACCEPTED, samples.size, events.size,
            droppedSamples = batch.samples.size - samples.size, droppedEvents = batch.events.size - inBatch.size,
        ).also { telemetryBatches[batch.clientBatchId] = it }
    }

    private val dtcReportsById = linkedMapOf<String, DtcReportDto>() // by clientReportId
    private val dtcFailures = ArrayDeque<ApiError>()

    /** Like [failNext], but only for the next DTC report posts. */
    fun failNextDtcReport(vararg errors: ApiError) {
        dtcFailures.addAll(errors)
    }

    /** (vehicle server id, Idempotency-Key) of every DTC report post, failed ones included. */
    val dtcReportRequests = mutableListOf<Pair<String, String>>()

    /** Stored DTC reports, oldest first. */
    val dtcReports: List<DtcReportDto> get() = dtcReportsById.values.toList()

    override suspend fun createDtcReport(vehicleServerId: String, report: DtcReportCreateDto, idempotencyKey: String): DtcReportDto {
        dtcReportRequests += vehicleServerId to idempotencyKey
        dtcFailures.removeFirstOrNull()?.let { calls += "POST /v1/vehicles/$vehicleServerId/dtc-reports"; throw it }
        return storeDtcReport(vehicleServerId, report)
    }

    private suspend fun storeDtcReport(vehicleServerId: String, report: DtcReportCreateDto) =
        op("POST /v1/vehicles/$vehicleServerId/dtc-reports") {
            dtcReportsById[report.clientReportId]?.let { return@op it } // 200 replay
            if (state.vehicles.values.none { it.id == vehicleServerId }) throw http(404, ApiError.CODE_VEHICLE_NOT_FOUND)
            val codes = report.stored.orEmpty() + report.pending.orEmpty() + report.permanent.orEmpty()
            if (codes.any { !DtcReportCreateDto.CODE.matches(it) } || report.vin?.let { !DtcReportCreateDto.VIN.matches(it) } == true ||
                report.kind !in setOf(DtcReportCreateDto.KIND_READ, DtcReportCreateDto.KIND_CLEARED)
            ) {
                throw http(422, "validation_failed")
            }
            DtcReportDto(
                id = "dtc_" + (++nextId), vehicleId = vehicleServerId, createdAt = clock(), clientReportId = report.clientReportId,
                kind = report.kind, readAt = report.readAt, mil = report.mil, stored = report.stored, pending = report.pending,
                permanent = report.permanent, vin = report.vin, clientTripId = report.clientTripId, odometerKm = report.odometerKm,
                distanceSinceMilKm = report.distanceSinceMilKm,
            ).also { dtcReportsById[report.clientReportId] = it }
        }

    override suspend fun deleteTrip(serverId: String) = op("DELETE /v1/trips/$serverId") {
        val trip = state.trips.values.firstOrNull { it.dto.id == serverId } ?: return@op // 404 counts as done
        deleteLocked(trip.dto.clientTripId)
    }

    // --- member features (#66 / #68): profile, stats, rankings, points ---

    private var profile: ProfileDto? = null

    /** Off: the stats, rankings and points endpoints answer `501` (a server that hasn't built them yet). */
    var memberFeaturesImplemented: Boolean = true

    /** Leaderboard page size (small, so the app's "load more" is exercised). */
    var rankingPageSize: Int = 10

    /** Ledger page size. */
    var ledgerPageSize: Int = 20

    /** Optional `today` for tests; defaults to the clock's date in the request's `tz`. */
    var today: LocalDate? = null

    private fun profileLocked(): ProfileDto = profile ?: clock().let { now ->
        ProfileDto(
            id = "fake-member", displayName = "You (Fake)", email = "driver@example.com", createdAt = now, updatedAt = now,
            countryCode = "IN", rankingOptIn = false, authProvider = "email_otp",
        ).also { profile = it }
    }

    private fun etagOf(p: ProfileDto) = "\"p" + p.updatedAt.toEpochMilli() + "\""

    private fun requireMemberFeatures() {
        if (!memberFeaturesImplemented) throw http(501, "not_implemented")
    }

    private fun day(date: String?, zone: java.time.ZoneId): LocalDate =
        date?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw http(400, ApiError.CODE_VALIDATION_FAILED) }
            ?: today ?: clock().atZone(zone).toLocalDate()

    private fun period(p: String) = p.takeIf { it in Periods.ALL_VALUES } ?: throw http(400, ApiError.CODE_VALIDATION_FAILED)

    private fun metric(m: String) = m.takeIf { it in RankingMetrics.ALL } ?: throw http(400, ApiError.CODE_VALIDATION_FAILED)

    override suspend fun getMe() = op("GET /v1/me") { profileLocked().let { Tagged(it, etagOf(it)) } }

    override suspend fun patchMe(patch: ProfilePatchDto, etag: String?) = op("PATCH /v1/me") {
        patchFailures.removeFirstOrNull()?.let { throw it }
        val current = profileLocked()
        if (etag.isNullOrBlank()) throw http(428, ApiError.CODE_PRECONDITION_REQUIRED)
        if (etag != etagOf(current)) throw http(412, ApiError.CODE_ETAG_MISMATCH)
        if (patch == ProfilePatchDto() || patch.displayName?.length?.let { it !in 1..40 } == true) throw http(422, ApiError.CODE_VALIDATION_FAILED)
        val now = clock().let { if (!it.isAfter(current.updatedAt)) current.updatedAt.plusMillis(1) else it }
        val updated = current.copy(
            displayName = patch.displayName ?: current.displayName, countryCode = patch.countryCode ?: current.countryCode,
            regionCode = patch.regionCode ?: current.regionCode, rankingOptIn = patch.rankingOptIn ?: current.rankingOptIn, updatedAt = now,
        )
        profile = updated
        Tagged(updated, etagOf(updated))
    }

    private fun periodTrips(period: String, date: String?, tz: String, vehicleId: String? = null, ranked: Boolean = false):
        Triple<LocalDate, LocalDate, List<TripDto>> {
        val zone = FakeMemberData.zone(tz)
        val all = FakeMemberData.completed(state.trips.values.map { it.dto })
            .filter { (vehicleId == null || it.vehicleId == vehicleId) && (!ranked || it.simulated != true) }
        val first = all.minOfOrNull { it.startedAt }?.atZone(zone)?.toLocalDate()
        val (from, to) = FakeMemberData.range(period(period), day(date, zone), first)
        return Triple(from, to, FakeMemberData.inRange(all, zone, from, to))
    }

    override suspend fun getStatsSummary(period: String, tz: String, date: String?, vehicleId: String?) =
        op("GET /v1/stats/summary?period=$period") {
            requireMemberFeatures()
            val (from, to, trips) = periodTrips(period, date, tz, vehicleId)
            FakeMemberData.summary(period, from, to, trips, FakeMemberData.completed(state.trips.values.map { it.dto }), vehicleId)
        }

    /** The leaderboard of [metric]: the made-up members, plus this member when opted in with ranked trips. */
    private fun board(metric: String, period: String, tz: String, date: String?): Pair<LocalDate, List<RankingEntryDto>> {
        val (from, _, trips) = periodTrips(period, date, tz, ranked = true)
        val me = profileLocked()
        val all = FakeMemberData.completed(state.trips.values.map { it.dto })
        val value = if (me.rankingOptIn) FakeMemberData.myValue(metric(metric), trips, all, pointsLocked().remaining) else null
        return from to FakeMemberData.leaderboard(metric, period, value?.let { me.displayName to it })
    }

    override suspend fun listRanking(metric: String, period: String, tz: String, date: String?, cursor: String?) =
        op("GET /v1/rankings/$metric?period=$period&cursor=$cursor") {
            requireMemberFeatures()
            val (start, rows) = board(metric(metric), period(period), tz, date)
            val offset = cursor?.let { c -> unb64(c)?.split('|')?.takeIf { it.size == 4 && it[0] == "r" && it[1] == metric && it[2] == period }?.get(3)?.toIntOrNull() }
                ?: if (cursor == null) 0 else throw http(400, "invalid_cursor")
            val page = rows.drop(offset).take(rankingPageSize)
            val more = offset + page.size < rows.size
            RankingPageDto(metric, period, start.toString(), page, if (more) b64("r|$metric|$period|${offset + page.size}") else null)
        }

    override suspend fun getMyRanking(metric: String, period: String, tz: String, date: String?) =
        op("GET /v1/rankings/$metric/me?period=$period") {
            requireMemberFeatures()
            val (_, rows) = board(metric(metric), period(period), tz, date)
            val me = rows.firstOrNull { it.isMe } ?: throw http(404, "not_ranked")
            MyRankingDto(
                metric, period, me.rank, rows.size, me.value,
                percentile = ((rows.size - me.rank) * 1000.0 / rows.size).toInt() / 10.0,
            )
        }

    private fun ledgerLocked() = FakeMemberData.ledger(profileLocked().createdAt, FakeMemberData.completed(state.trips.values.map { it.dto }))

    private fun pointsLocked() = FakeMemberData.balance(ledgerLocked())

    override suspend fun getPointsBalance() = op("GET /v1/me/points") {
        requireMemberFeatures()
        pointsLocked()
    }

    override suspend fun listPointsLedger(cursor: String?) = op("GET /v1/me/points/ledger?cursor=$cursor") {
        requireMemberFeatures()
        val rows = ledgerLocked()
        val offset = cursor?.let { c -> unb64(c)?.split('|')?.takeIf { it.size == 2 && it[0] == "l" }?.get(1)?.toIntOrNull() ?: throw http(400, "invalid_cursor") } ?: 0
        val page = rows.drop(offset).take(ledgerPageSize)
        PointsLedgerPageDto(page, if (offset + page.size < rows.size) b64("l|${offset + page.size}") else null)
    }

    /** Debug Simulator scenarios (#66): the next matching call fails the way a real server can. */
    enum class Scenario {
        /** The next `PATCH` (vehicle or profile) answers `412 etag_mismatch`. */
        CONFLICT_412,

        /** The next call answers `500` with a problem+json body. */
        PROBLEM_500,

        /** The next call answers `503` with `Retry-After: 5`. */
        UNAVAILABLE_503,
    }

    fun arm(scenario: Scenario) {
        when (scenario) {
            Scenario.CONFLICT_412 -> patchFailures.add(http(412, ApiError.CODE_ETAG_MISMATCH))
            Scenario.PROBLEM_500 -> failures.add(
                ApiError.Http(500, problem(500, "internal_error").copy(title = "Internal Server Error", detail = "Simulated server error (Fake mode)")),
            )
            Scenario.UNAVAILABLE_503 -> failures.add(unavailable())
        }
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
