package io.github.neotheon3.orangebox.core.database

import io.github.neotheon3.orangebox.core.model.VehicleFuel
import io.github.neotheon3.orangebox.core.network.BackendJson
import io.github.neotheon3.orangebox.core.network.DtcReportCreateDto
import io.github.neotheon3.orangebox.core.network.EndReasons
import io.github.neotheon3.orangebox.core.network.FuelTypes
import io.github.neotheon3.orangebox.core.network.EventDto
import io.github.neotheon3.orangebox.core.network.SampleDto
import io.github.neotheon3.orangebox.core.network.TelemetryBatchDto
import io.github.neotheon3.orangebox.core.network.TripDto
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.network.UtcInstantSerializer
import io.github.neotheon3.orangebox.core.network.VehicleCreateDto
import io.github.neotheon3.orangebox.core.network.VehicleDto
import io.github.neotheon3.orangebox.core.sync.LocalDeletion
import io.github.neotheon3.orangebox.core.sync.PendingTelemetryBatch
import io.github.neotheon3.orangebox.core.sync.PendingTripBatch
import io.github.neotheon3.orangebox.core.sync.QueuedDtcReport
import io.github.neotheon3.orangebox.core.sync.TelemetryChunk
import io.github.neotheon3.orangebox.core.sync.TelemetryTrip
import io.github.neotheon3.orangebox.core.sync.SyncStore
import io.github.neotheon3.orangebox.core.sync.SyncTrip
import io.github.neotheon3.orangebox.core.sync.SyncVehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToLong

/** [SyncStore] over Room (schema 6). Behaves like the engine tests' InMemorySyncStore. */
class RoomSyncStore(private val dao: SyncDao, private val clock: () -> Long, private val newId: () -> String) : SyncStore {
    @Inject constructor(dao: SyncDao) : this(dao, System::currentTimeMillis, { UUID.randomUUID().toString() })
    constructor(dao: SyncDao, clock: () -> Long) : this(dao, clock, { UUID.randomUUID().toString() })

    override suspend fun since(): String? = dao.state(KEY_SINCE)
    override suspend fun storeSince(token: String) = dao.putState(SyncStateEntity(KEY_SINCE, token))
    override suspend fun clearSince() = dao.deleteState(KEY_SINCE)

    override suspend fun vehicles(): List<SyncVehicle> {
        val merged = dao.stateKeys(KEY_MERGED_VEHICLE).map { it.removePrefix(KEY_MERGED_VEHICLE) }.toSet()
        return dao.vehiclesWithSync().map { v -> syncVehicle(v, v.clientVehicleId in merged) }
    }

    private fun syncVehicle(v: VehicleWithSync, merged: Boolean) =
        SyncVehicle(
            clientVehicleId = v.clientVehicleId,
            serverId = v.serverId,
            create = VehicleCreateDto(
                clientVehicleId = v.clientVehicleId,
                // Only fuelType is required (draft.9): make and model go when known, never as ""; the name is
                // the nickname.
                make = v.maker?.trim()?.takeIf { it.isNotEmpty() },
                model = v.model?.trim()?.takeIf { it.isNotEmpty() },
                fuelType = wireFuel(v.fuel),
                nickname = v.name.take(40),
                engineDisplacementCc = v.displacementCc,
                odometerKm = v.odometerKm,
            ),
            goneOnServer = v.goneOnServer == true,
            account = v.accountId,
            merged = merged,
        )

    override suspend fun setVehicleServerId(clientVehicleId: String, serverId: String?) {
        val v = dao.vehicleByClientId(clientVehicleId) ?: return
        val row = dao.vehicleSync(v.id) ?: VehicleSyncEntity(v.id)
        dao.upsertVehicleSync(row.copy(serverId = serverId, goneOnServer = false))
        if (serverId != null) dao.deleteState(KEY_MERGED_VEHICLE + clientVehicleId)
    }

    override suspend fun renewClientVehicleId(clientVehicleId: String): String? {
        val v = dao.vehicleByClientId(clientVehicleId) ?: return null
        val fresh = newId()
        // Queued DTC reports name the vehicle by clientVehicleId: move them along.
        val reports = dtcReports().filter { it.clientVehicleId == clientVehicleId }
        dao.updateVehicle(v.copy(clientVehicleId = fresh))
        reports.forEach { putDtcReport(fresh, it.report) }
        dao.deleteState(KEY_MERGED_VEHICLE + clientVehicleId)
        dao.deleteState(KEY_VEHICLE_UPDATED + clientVehicleId)
        return fresh
    }

    override suspend fun markVehicleGone(clientVehicleId: String) {
        val v = dao.vehicleByClientId(clientVehicleId) ?: return
        dao.upsertVehicleSync((dao.vehicleSync(v.id) ?: VehicleSyncEntity(v.id)).copy(serverId = null, goneOnServer = true))
    }

    override suspend fun applyRemoteVehicle(clientVehicleId: String, remote: VehicleDto): Boolean {
        val v = dao.vehicleByClientId(clientVehicleId) ?: return false
        val key = KEY_VEHICLE_UPDATED + clientVehicleId
        val seen = dao.state(key)?.toLongOrNull()
        val at = remote.updatedAt.toEpochMilli()
        if (seen == null || at > seen) dao.putState(SyncStateEntity(key, at.toString()))
        if (seen == null || at <= seen) return false
        // An edit from another device. Fuel types we can't map back (other) keep the local value.
        dao.updateVehicle(
            v.copy(
                name = remote.nickname?.takeIf { it.isNotBlank() } ?: v.name,
                maker = remote.knownMake ?: v.maker,
                model = remote.knownModel ?: v.model,
                fuel = localFuel(remote.fuelType) ?: v.fuel,
                displacementCc = remote.engineDisplacementCc ?: v.displacementCc,
                odometerKm = remote.odometerKm ?: v.odometerKm,
            ),
        )
        return true
    }

    override suspend fun deleteVehicle(clientVehicleId: String, keepClientTripIds: Set<String>) {
        val v = dao.vehicleByClientId(clientVehicleId) ?: return
        dao.tripClientIdsOfVehicle(v.id).filter { it !in keepClientTripIds }.forEach { dao.deleteTrip(it) }
        dao.deleteVehicle(v.id)
    }

    override suspend fun trips(): List<SyncTrip> {
        val vehicles = dao.vehiclesWithSync().associateBy { it.id }
        return dao.tripsWithSync().map { t ->
            SyncTrip(
                clientTripId = t.clientTripId,
                serverId = t.serverId,
                clientVehicleId = t.vehicleId?.let { vehicles[it]?.clientVehicleId },
                serverUpdatedAt = t.serverUpdatedAt?.let { runCatching { UtcInstantSerializer.parse(it) }.getOrNull() },
                uploadedBefore = t.uploadedBefore == true,
                needsUpload = needsUpload(t),
                remoteOrigin = t.remoteOrigin == true,
                acceptedSnapshotAt = t.acceptedSnapshotAtMillis?.let(Instant::ofEpochMilli),
                completedLocally = t.endedAtMillis != null,
                account = t.accountId,
            )
        }
    }

    private fun needsUpload(t: TripWithSync): Boolean {
        if (t.remoteOrigin == true || t.rejectedCode != null) return false
        if (t.forceUpload == true) return true
        val accepted = t.acceptedSnapshotAtMillis ?: return true
        return t.lastSampleAtMillis > accepted || (t.endedAtMillis != null && t.acceptedCompleted != true)
    }

    override suspend fun applyRemoteTrip(trip: TripDto, account: String?) {
        val local = dao.tripByClientId(trip.clientTripId)
        if (local != null && local.accountId != account) return // another account's trip with the same id: not ours
        val vehicleId = dao.vehicleIdByServerId(trip.vehicleId)
        val tripId = if (local == null) {
            dao.insertTrip(remoteToEntity(trip, vehicleId).copy(accountId = account))
        } else {
            val sync = dao.tripSync(local.id)
            // Our own trips keep their local summary (they have the samples); pulled ones take the newer copy.
            if (sync?.remoteOrigin == true) dao.updateTrip(remoteToEntity(trip, vehicleId).copy(id = local.id, accountId = local.accountId, excludedFromStats = local.excludedFromStats))
            local.id
        }
        val row = dao.tripSync(tripId) ?: TripSyncEntity(tripId, remoteOrigin = local == null)
        dao.upsertTripSync(
            row.copy(
                serverId = trip.id, serverUpdatedAt = trip.updatedAt.toString(), uploadedBefore = true,
                acceptedSnapshotAtMillis = row.acceptedSnapshotAtMillis ?: trip.snapshotAt.toEpochMilli(),
                acceptedCompleted = row.acceptedCompleted || trip.state == TripUploadDto.STATE_COMPLETED,
            ),
        )
    }

    private fun remoteToEntity(t: TripDto, vehicleId: Long?) = TripEntity(
        clientTripId = t.clientTripId,
        vehicleId = vehicleId,
        startedAtMillis = t.startedAt.toEpochMilli(),
        endedAtMillis = t.endedAt?.toEpochMilli(),
        lastSampleAtMillis = (t.endedAt ?: t.snapshotAt).toEpochMilli(),
        endReason = if (t.endedAt != null) EndReasons.read(t.endReason) ?: EndReasons.OTHER else null,
        durationS = t.durationS?.toDouble() ?: 0.0,
        distanceKm = t.distanceKm ?: 0.0,
        fuelLitres = t.fuelUsedL ?: 0.0,
        idleS = t.idleTimeS ?: 0.0,
        fuelCutS = t.fuelCutTimeS ?: 0.0,
        ecoS = t.ecoSpeedTimeS ?: 0.0,
        overspeedS = t.overSpeedTimeS ?: 0.0,
        harshAccelCount = t.harshAccelCount ?: 0,
        harshBrakeCount = t.harshBrakeCount ?: 0,
        overspeedCount = t.overSpeedCount ?: 0,
        maxSpeedKph = t.maxSpeedKmh ?: 0.0,
    )

    override suspend fun deleteTripLocally(clientTripId: String) {
        dao.deleteTrip(clientTripId)
        dao.deleteState(KEY_TELEMETRY + clientTripId)
    }

    override suspend fun localDeletions(): List<LocalDeletion> =
        dao.localDeletions().map { LocalDeletion(it.clientTripId, it.serverId, it.pushed) }

    override suspend fun markLocalDeletionPushed(clientTripId: String) = dao.markDeletionPushed(clientTripId)

    override suspend fun uploadOf(clientTripId: String): TripUploadDto? {
        val t = dao.tripByClientId(clientTripId) ?: return null
        val vehicleId = t.vehicleId ?: return null
        val vehicleSync = dao.vehicleSync(vehicleId)
        val serverVehicle = vehicleSync?.serverId ?: return null
        val sync = dao.tripSync(t.id)
        val completed = t.endedAtMillis != null
        var snapshot = maxOf(t.lastSampleAtMillis, t.endedAtMillis ?: 0)
        // Completing must be newer than the last accepted in-progress snapshot, or the server ignores it.
        sync?.acceptedSnapshotAtMillis?.let { if (completed && sync.acceptedCompleted.not() && snapshot <= it) snapshot = it + 1 }
        val avg = if (t.durationS > 0) t.distanceKm / t.durationS * 3600 else null
        return TripUploadDto(
            clientTripId = t.clientTripId,
            vehicleId = serverVehicle,
            state = if (completed) TripUploadDto.STATE_COMPLETED else TripUploadDto.STATE_IN_PROGRESS,
            startedAt = Instant.ofEpochMilli(t.startedAtMillis),
            snapshotAt = Instant.ofEpochMilli(snapshot),
            endedAt = t.endedAtMillis?.let(Instant::ofEpochMilli),
            durationS = t.durationS.roundToLong(),
            distanceKm = t.distanceKm,
            maxSpeedKmh = t.maxSpeedKph,
            avgSpeedKmh = avg,
            harshAccelCount = t.harshAccelCount,
            harshBrakeCount = t.harshBrakeCount,
            fuelUsedL = t.fuelLitres,
            fuelEconomyKmPerL = if (t.fuelLitres > 0) t.distanceKm / t.fuelLitres else null,
            fuelCutTimeS = t.fuelCutS,
            idleTimeS = t.idleS,
            ecoSpeedTimeS = t.ecoS,
            overSpeedTimeS = t.overspeedS,
            overSpeedCount = t.overspeedCount,
            fuelCutCount = dao.countEvents(t.id, EVENT_FUEL_CUT),
            endReason = if (completed) wireEndReason(t.endReason) else null,
        )
    }

    override suspend fun markUploaded(clientTripId: String, serverId: String, snapshotAt: Instant) {
        val t = dao.tripByClientId(clientTripId) ?: return
        val row = dao.tripSync(t.id) ?: TripSyncEntity(t.id)
        dao.upsertTripSync(
            row.copy(
                serverId = serverId, uploadedBefore = true, acceptedSnapshotAtMillis = snapshotAt.toEpochMilli(),
                acceptedCompleted = t.endedAtMillis != null, forceUpload = false, rejectedCode = null,
            ),
        )
    }

    override suspend fun markRejected(clientTripId: String, code: String?) {
        val t = dao.tripByClientId(clientTripId) ?: return
        dao.upsertTripSync((dao.tripSync(t.id) ?: TripSyncEntity(t.id)).copy(rejectedCode = code ?: "rejected"))
    }

    override suspend fun requeueUpload(clientTripIds: Set<String>) {
        for (id in clientTripIds) {
            val t = dao.tripByClientId(id) ?: continue
            dao.upsertTripSync((dao.tripSync(t.id) ?: TripSyncEntity(t.id)).copy(serverId = null, forceUpload = true, rejectedCode = null))
        }
    }

    @Serializable private data class StoredBatch(val idempotencyKey: String, val trips: List<TripUploadDto>)

    override suspend fun pendingTripBatch(): PendingTripBatch? = dao.state(KEY_PENDING_BATCH)?.let { json ->
        runCatching { BackendJson.decodeFromString(StoredBatch.serializer(), json) }.getOrNull()
            ?.let { PendingTripBatch(it.idempotencyKey, it.trips) }
    }

    override suspend fun setPendingTripBatch(batch: PendingTripBatch?) {
        if (batch == null) dao.deleteState(KEY_PENDING_BATCH)
        else dao.putState(SyncStateEntity(KEY_PENDING_BATCH, BackendJson.encodeToString(StoredBatch.serializer(), StoredBatch(batch.idempotencyKey, batch.trips))))
    }

    // --- telemetry (#67) ---

    /** Per-trip progress in `sync_state`: `"<lastSampleRowId>|<lastEventRowId>"`, or `"done|<code>"`. */
    private suspend fun progress(clientTripId: String): Pair<Long, Long>? {
        val v = dao.state(KEY_TELEMETRY + clientTripId) ?: return 0L to 0L
        if (v.startsWith(DONE)) return null
        val p = v.split('|')
        return (p.getOrNull(0)?.toLongOrNull() ?: 0L) to (p.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    override suspend fun telemetryTrips(account: String?): List<TelemetryTrip> {
        val done = dao.stateKeys(KEY_TELEMETRY).filter { dao.state(it)?.startsWith(DONE) == true }.map { it.removePrefix(KEY_TELEMETRY) }.toSet()
        return dao.telemetryCandidates(clock() - TELEMETRY_BACKLOG_DAYS * DAY_MILLIS, account)
            .filter { it.clientTripId !in done }
            .map { TelemetryTrip(it.clientTripId, it.vehicleServerId) }
    }

    override suspend fun telemetryChunk(clientTripId: String, maxSamples: Int, maxEvents: Int): TelemetryChunk? {
        var (afterSample, afterEvent) = progress(clientTripId) ?: return null
        while (true) {
            val samples = dao.samplesAfter(clientTripId, afterSample, maxSamples)
            val events = dao.eventsAfter(clientTripId, afterEvent, maxEvents)
            if (samples.isEmpty() && events.isEmpty()) return null
            val sampleDtos = samples.map(::sampleDto)
            val eventDtos = events.mapNotNull(::eventDto)
            val upToSample = samples.lastOrNull()?.id ?: afterSample
            val upToEvent = events.lastOrNull()?.id ?: afterEvent
            if (sampleDtos.isNotEmpty() || eventDtos.isNotEmpty()) return TelemetryChunk(sampleDtos, eventDtos, upToSample, upToEvent)
            // Only events the spec has no type for: skip past them without a request.
            markTelemetrySent(clientTripId, upToSample, upToEvent)
            afterSample = upToSample
            afterEvent = upToEvent
        }
    }

    override suspend fun markTelemetrySent(clientTripId: String, samplesUpTo: Long, eventsUpTo: Long) =
        dao.putState(SyncStateEntity(KEY_TELEMETRY + clientTripId, "$samplesUpTo|$eventsUpTo"))

    override suspend fun markTelemetryDone(clientTripId: String, code: String?) =
        dao.putState(SyncStateEntity(KEY_TELEMETRY + clientTripId, DONE + (code ?: "")))

    @Serializable private data class StoredTelemetry(val idempotencyKey: String, val batch: TelemetryBatchDto, val samplesUpTo: Long, val eventsUpTo: Long)

    override suspend fun pendingTelemetryBatch(): PendingTelemetryBatch? = dao.state(KEY_PENDING_TELEMETRY)?.let { json ->
        runCatching { BackendJson.decodeFromString(StoredTelemetry.serializer(), json) }.getOrNull()
            ?.let { PendingTelemetryBatch(it.idempotencyKey, it.batch, it.samplesUpTo, it.eventsUpTo) }
    }

    override suspend fun setPendingTelemetryBatch(batch: PendingTelemetryBatch?) {
        if (batch == null) dao.deleteState(KEY_PENDING_TELEMETRY)
        else dao.putState(
            SyncStateEntity(
                KEY_PENDING_TELEMETRY,
                BackendJson.encodeToString(StoredTelemetry.serializer(), StoredTelemetry(batch.idempotencyKey, batch.batch, batch.samplesUpTo, batch.eventsUpTo)),
            ),
        )
    }

    // --- DTC reports (#67), queued in sync_state so sign-out (forgetServer) drops them with the rest ---

    @Serializable private data class StoredDtcReport(val clientVehicleId: String, val report: DtcReportCreateDto)

    /**
     * Queues a DTC read or clear of the local vehicle [vehicleId] for the next sync. Returns false when the vehicle
     * doesn't exist. Keeps at most [MAX_QUEUED_DTC_REPORTS], dropping the oldest.
     */
    suspend fun enqueueDtcReport(vehicleId: Long, report: DtcReportCreateDto): Boolean {
        val clientVehicleId = dao.clientVehicleIdOf(vehicleId) ?: return false
        putDtcReport(clientVehicleId, report)
        dtcReports().dropLast(MAX_QUEUED_DTC_REPORTS).forEach { removeDtcReport(it.report.clientReportId) }
        return true
    }

    /** Oldest first; drops unreadable rows, reports older than the backlog bound and those of deleted vehicles. */
    override suspend fun dtcReports(): List<QueuedDtcReport> {
        val cutoff = clock() - TELEMETRY_BACKLOG_DAYS * DAY_MILLIS
        return dao.stateKeys(KEY_DTC_REPORT).mapNotNull { key ->
            val stored = dao.state(key)?.let { runCatching { BackendJson.decodeFromString(StoredDtcReport.serializer(), it) }.getOrNull() }
            if (stored == null || stored.report.readAt.toEpochMilli() < cutoff || dao.vehicleByClientId(stored.clientVehicleId) == null) {
                dao.deleteState(key)
                null
            } else {
                QueuedDtcReport(stored.clientVehicleId, stored.report)
            }
        }.sortedWith(compareBy({ it.report.readAt }, { it.report.clientReportId }))
    }

    private suspend fun putDtcReport(clientVehicleId: String, report: DtcReportCreateDto) = dao.putState(
        SyncStateEntity(KEY_DTC_REPORT + report.clientReportId, BackendJson.encodeToString(StoredDtcReport.serializer(), StoredDtcReport(clientVehicleId, report))),
    )

    override suspend fun removeDtcReport(clientReportId: String) = dao.deleteState(KEY_DTC_REPORT + clientReportId)

    // --- accounts (Q13): every trip and vehicle carries the account that recorded or pulled it ---

    /** Sign-in: new trips and vehicles from now on belong to [account]; null (sign-out) leaves them unassigned. */
    suspend fun setAccount(account: String?) {
        if (account == null) dao.deleteState(ACCOUNT_STATE_KEY) else dao.putState(SyncStateEntity(ACCOUNT_STATE_KEY, account))
    }

    suspend fun account(): String? = dao.state(ACCOUNT_STATE_KEY)

    /** Trips on this phone that aren't [account]'s: [count], of which [neverUploaded] exist nowhere else. */
    data class ForeignTrips(val count: Int, val neverUploaded: Int)

    /** Trips recorded signed out (never uploaded to any account) or another account's not uploaded there. */
    suspend fun foreignTrips(account: String): ForeignTrips {
        val trips = dao.foreignTrips(account)
        return ForeignTrips(trips.size, trips.count { it.accountId == null || it.uploadedBefore != true })
    }

    fun observeForeignTripCount(account: String): Flow<Int> = dao.observeForeignTripCount(account)

    /** The sign-in choice (merge / replace) is waiting for an answer; nothing syncs until it's answered or dismissed. */
    suspend fun choicePending(): Boolean = dao.state(KEY_CHOICE_PENDING) != null

    fun observeChoicePending(): Flow<Boolean> = dao.observeState(KEY_CHOICE_PENDING).map { it != null }

    suspend fun setChoicePending(pending: Boolean) {
        if (pending) dao.putState(SyncStateEntity(KEY_CHOICE_PENDING, "1")) else dao.deleteState(KEY_CHOICE_PENDING)
    }

    /**
     * "Merge with account": the trips that aren't [account]'s become its own and upload to it, with their vehicles
     * and those added signed out. The old account's server ids (and telemetry progress) on those rows are dropped;
     * `clientTripId` / `clientVehicleId` are reused (unique per account). Merged vehicles not yet known to the
     * account are marked so a `410` re-creates them under a fresh id. Returns how many trips were merged.
     */
    suspend fun mergeInto(account: String): Int {
        val trips = dao.foreignTrips(account)
        for (t in trips) {
            dao.stampTrip(t.id, account)
            dao.deleteTripSync(t.id)
            dao.deleteState(KEY_TELEMETRY + t.clientTripId)
        }
        val vehicles = dao.vehiclesWithSync().associateBy { it.id }
        val ids = trips.mapNotNull { it.vehicleId }.toSet() + dao.unassignedVehicles()
        for (id in ids) {
            val v = vehicles[id] ?: continue
            if (v.accountId != account) dao.stampVehicle(id, account)
            if (v.serverId == null) dao.putState(SyncStateEntity(KEY_MERGED_VEHICLE + v.clientVehicleId, "1"))
        }
        return trips.size
    }

    /**
     * "Replace with account's trips": deletes the trips that aren't [account]'s from this phone (no tombstones: the
     * other account's server copies stay). Vehicles stay; those added signed out join the account. Returns the count.
     */
    suspend fun replaceWith(account: String): Int {
        val trips = dao.foreignTrips(account)
        for (t in trips) {
            dao.deleteTripById(t.id)
            dao.deleteState(KEY_TELEMETRY + t.clientTripId)
        }
        adoptUnassignedVehicles(account)
        return trips.size
    }

    /** Vehicles added while signed out join [account] (no trips are involved, so no prompt is needed). */
    suspend fun adoptUnassignedVehicles(account: String) {
        dao.unassignedVehicles().forEach { dao.stampVehicle(it, account) }
    }

    override suspend fun pausedUntil(step: String): Long? = dao.state(KEY_PAUSED + step)?.toLongOrNull()

    override suspend fun pauseUntil(step: String, atMillis: Long?) {
        if (atMillis == null) dao.deleteState(KEY_PAUSED + step) else dao.putState(SyncStateEntity(KEY_PAUSED + step, atMillis.toString()))
    }

    companion object {
        const val KEY_MERGED_VEHICLE = "merged_vehicle:"
        const val KEY_CHOICE_PENDING = "account_choice_pending"
        const val KEY_PAUSED = "paused:"
        const val KEY_VEHICLE_UPDATED = "vehicle_updated:"
        const val KEY_DTC_REPORT = "dtc_report:"

        /** Offline bound on queued DTC reports (a read per drive start is far below it). */
        const val MAX_QUEUED_DTC_REPORTS = 100
        const val KEY_TELEMETRY = "telemetry:"
        const val KEY_PENDING_TELEMETRY = "pending_telemetry_batch"
        private const val DONE = "done|"
        private const val DAY_MILLIS = 86_400_000L

        /**
         * Offline backlog bound: telemetry of trips older than this isn't uploaded (#67 proposes 30 days / 100 MB).
         * TODO(#67): the 100 MB size bound isn't enforced yet; the spec only lists it as a proposal.
         */
        const val TELEMETRY_BACKLOG_DAYS = 30L

        /**
         * Local event type → spec `EventType`. `fuel_cut` has no spec value, so it isn't sent (its time is in the
         * trip summary's `fuelCutTimeS`).
         * Confirmed: `fuel_cut` stays summary-only; CO2 is not sent (not needed); `ecoSpeedTimeS` is kept.
         */
        val EVENT_TYPES: Map<String, String> = mapOf(
            "harsh_accel" to "harsh_accel",
            "harsh_brake" to "harsh_brake",
            "overspeed" to "overspeed",
        )

        /**
         * Spec `Sample` from a recorded sample. Fuel flow goes only as the car's own PID 5E (`pids["0x5E"]`, L/h),
         * with `mafGps` when PID 10 was read; our computed flow isn't sent.
         * The server derives flow from those (draft.9 has no computed-flow field).
         */
        fun sampleDto(s: TripSampleEntity) = SampleDto(
            t = Instant.ofEpochMilli(s.timestampMillis),
            lat = s.latitude,
            lon = s.longitude,
            speedKmh = s.speedKph,
            rpm = s.rpm,
            coolantC = s.coolantC,
            mafGps = s.mafGps,
            pids = s.fuelRatePidLph?.let { mapOf(PID_FUEL_RATE to it) },
        )

        const val PID_FUEL_RATE = "0x5E"

        fun eventDto(e: TripEventEntity): EventDto? = EVENT_TYPES[e.type]?.let { type ->
            EventDto(
                t = Instant.ofEpochMilli(e.timestampMillis),
                type = type,
                clientEventId = e.clientEventId.takeIf { it.isNotBlank() },
                lat = e.latitude,
                lon = e.longitude,
                value = e.value,
            )
        }

        const val KEY_SINCE = "trips_since"
        const val KEY_PENDING_BATCH = "pending_trip_batch"
        /** The recorder's `fuel_cut` events: counted for `fuelCutCount`, never sent as events. */
        const val EVENT_FUEL_CUT = "fuel_cut"

        /** Local trip end reason → spec `endReason`; anything else (a demo trip, unknown) is `other`. */
        fun wireEndReason(local: String?): String = local?.takeIf { it in EndReasons.KNOWN } ?: EndReasons.OTHER
        /**
         * Spec `FuelType` → Orange Box fuel, for edits from another device; null when it has no local match (hybrid,
         * electric, other, or a value newer than this app), which keeps the local fuel.
         */
        fun localFuel(wire: String): String? = when (wire) {
            FuelTypes.GASOLINE -> VehicleFuel.PETROL.name
            FuelTypes.DIESEL -> VehicleFuel.DIESEL.name
            FuelTypes.LPG, FuelTypes.GASOLINE_LPG -> VehicleFuel.LPG.name
            FuelTypes.CNG, FuelTypes.GASOLINE_CNG -> VehicleFuel.CNG.name
            FuelTypes.ETHANOL, FuelTypes.FLEX -> VehicleFuel.ETHANOL.name
            else -> null
        }

        /**
         * Orange Box fuel → spec `FuelType` (draft.9; `gasoline` is petrol). CNG is `gasoline_cng`: the CNG presets
         * (SX4, Celerio, Ertiga factory CNG) are bi-fuel cars with a petrol tank, and the app doesn't record a
         * CNG-only car separately. Vehicles created as `other` before draft.9 are PATCHed by the sync.
         */
        fun wireFuel(fuel: String): String = when (VehicleFuel.entries.firstOrNull { it.name == fuel }) {
            VehicleFuel.PETROL, null -> FuelTypes.GASOLINE
            VehicleFuel.DIESEL -> FuelTypes.DIESEL
            VehicleFuel.LPG -> FuelTypes.LPG
            VehicleFuel.CNG -> FuelTypes.GASOLINE_CNG
            VehicleFuel.ETHANOL -> FuelTypes.ETHANOL
        }
    }
}
