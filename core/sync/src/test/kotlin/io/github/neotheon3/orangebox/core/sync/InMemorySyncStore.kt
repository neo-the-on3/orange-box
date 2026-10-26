package io.github.neotheon3.orangebox.core.sync

import io.github.neotheon3.orangebox.core.network.EventDto
import io.github.neotheon3.orangebox.core.network.SampleDto
import io.github.neotheon3.orangebox.core.network.TripDto
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.network.VehicleCreateDto
import java.time.Instant
import java.util.UUID

/** Reference [SyncStore] for the engine tests; the Room store must behave the same. */
class InMemorySyncStore : SyncStore {
    data class Trip(
        val clientTripId: String,
        val clientVehicleId: String?,
        val snapshotAt: Instant,
        val completed: Boolean = true,
        val distanceKm: Double = 1.0,
        val serverId: String? = null,
        val serverUpdatedAt: Instant? = null,
        val uploadedBefore: Boolean = false,
        val acceptedSnapshotAt: Instant? = null,
        val rejected: String? = null,
        val remoteOrigin: Boolean = false,
        val forceUpload: Boolean = false,
    )

    var since: String? = null
    val vehicles = linkedMapOf<String, SyncVehicle>()
    val trips = linkedMapOf<String, Trip>()
    val deletions = linkedMapOf<String, LocalDeletion>()
    var pending: PendingTripBatch? = null

    /** Telemetry rows per trip, keyed by a row id like Room's. */
    val samples = linkedMapOf<String, MutableList<Pair<Long, SampleDto>>>()
    val events = linkedMapOf<String, MutableList<Pair<Long, EventDto>>>()
    val telemetrySent = mutableMapOf<String, Pair<Long, Long>>()
    val telemetryDone = mutableMapOf<String, String?>()
    var pendingTelemetry: PendingTelemetryBatch? = null
    private var rowId = 0L

    fun addTelemetry(clientTripId: String, sampleCount: Int, eventCount: Int = 0, from: Instant = trips.getValue(clientTripId).snapshotAt.minusSeconds(600)) {
        repeat(sampleCount) { samples.getOrPut(clientTripId) { mutableListOf() } += ++rowId to SampleDto(t = from.plusSeconds(it.toLong()), speedKmh = 40.0) }
        repeat(eventCount) {
            events.getOrPut(clientTripId) { mutableListOf() } += ++rowId to EventDto(t = from.plusSeconds(it * 10L), type = "harsh_brake", clientEventId = UUID.randomUUID().toString())
        }
    }

    fun addVehicle(name: String = "SX4"): String {
        val id = UUID.randomUUID().toString()
        vehicles[id] = SyncVehicle(id, null, VehicleCreateDto(id, "Maruti Suzuki", name, "gasoline"))
        return id
    }

    fun record(vehicle: String, at: Instant = Instant.parse("2026-10-20T10:00:00Z"), completed: Boolean = true): String {
        val id = UUID.randomUUID().toString()
        trips[id] = Trip(id, vehicle, at, completed)
        return id
    }

    /** The user deletes a trip on this device. */
    fun deleteHere(clientTripId: String) {
        val t = trips.remove(clientTripId) ?: return
        deletions[clientTripId] = LocalDeletion(clientTripId, t.serverId, pushed = false)
    }

    override suspend fun since() = since
    override suspend fun storeSince(token: String) { since = token }
    override suspend fun clearSince() { since = null }
    override suspend fun vehicles() = vehicles.values.toList()
    override suspend fun setVehicleServerId(clientVehicleId: String, serverId: String?) {
        vehicles[clientVehicleId] = vehicles.getValue(clientVehicleId).copy(serverId = serverId)
    }
    override suspend fun markVehicleGone(clientVehicleId: String) {
        vehicles[clientVehicleId] = vehicles.getValue(clientVehicleId).copy(goneOnServer = true)
    }
    override suspend fun deleteVehicle(clientVehicleId: String, keepClientTripIds: Set<String>) {
        vehicles.remove(clientVehicleId)
        trips.values.filter { it.clientVehicleId == clientVehicleId && it.clientTripId !in keepClientTripIds }.forEach { trips.remove(it.clientTripId) }
    }

    override suspend fun trips() = trips.values.map {
        SyncTrip(
            clientTripId = it.clientTripId, serverId = it.serverId, clientVehicleId = it.clientVehicleId,
            serverUpdatedAt = it.serverUpdatedAt, uploadedBefore = it.uploadedBefore,
            needsUpload = !it.remoteOrigin && it.rejected == null && (it.forceUpload || it.acceptedSnapshotAt == null || it.snapshotAt.isAfter(it.acceptedSnapshotAt)),
            remoteOrigin = it.remoteOrigin,
        )
    }

    override suspend fun applyRemoteTrip(trip: TripDto) {
        val local = trips[trip.clientTripId]
        trips[trip.clientTripId] = local?.copy(serverId = trip.id, serverUpdatedAt = trip.updatedAt)
            ?: Trip(
                trip.clientTripId, vehicles.values.firstOrNull { it.serverId == trip.vehicleId }?.clientVehicleId, trip.snapshotAt,
                trip.state == TripUploadDto.STATE_COMPLETED, trip.distanceKm ?: 0.0, trip.id, trip.updatedAt,
                uploadedBefore = true, acceptedSnapshotAt = trip.snapshotAt, remoteOrigin = true,
            )
    }

    override suspend fun deleteTripLocally(clientTripId: String) { trips.remove(clientTripId) }
    override suspend fun localDeletions() = deletions.values.toList()
    override suspend fun markLocalDeletionPushed(clientTripId: String) {
        deletions[clientTripId] = deletions.getValue(clientTripId).copy(pushed = true)
    }

    override suspend fun uploadOf(clientTripId: String): TripUploadDto? {
        val t = trips[clientTripId] ?: return null
        val vehicle = t.clientVehicleId?.let { vehicles[it] }?.serverId ?: return null
        return TripUploadDto(
            clientTripId = t.clientTripId, vehicleId = vehicle,
            state = if (t.completed) TripUploadDto.STATE_COMPLETED else TripUploadDto.STATE_IN_PROGRESS,
            startedAt = t.snapshotAt.minusSeconds(600), snapshotAt = t.snapshotAt,
            endedAt = if (t.completed) t.snapshotAt else null, distanceKm = t.distanceKm,
        )
    }

    override suspend fun markUploaded(clientTripId: String, serverId: String, snapshotAt: Instant) {
        val t = trips[clientTripId] ?: return
        trips[clientTripId] = t.copy(serverId = serverId, uploadedBefore = true, acceptedSnapshotAt = snapshotAt, forceUpload = false)
    }

    override suspend fun markRejected(clientTripId: String, code: String?) {
        trips[clientTripId]?.let { trips[clientTripId] = it.copy(rejected = code ?: "rejected") }
    }

    override suspend fun requeueUpload(clientTripIds: Set<String>) {
        clientTripIds.forEach { id -> trips[id]?.let { trips[id] = it.copy(forceUpload = true, serverId = null, rejected = null) } }
    }

    override suspend fun pendingTripBatch() = pending
    override suspend fun setPendingTripBatch(batch: PendingTripBatch?) { pending = batch }

    override suspend fun telemetryTrips() = trips.values
        .filter { it.completed && !it.remoteOrigin && it.uploadedBefore && it.clientTripId !in telemetryDone }
        .mapNotNull { t -> t.clientVehicleId?.let { vehicles[it]?.serverId }?.let { TelemetryTrip(t.clientTripId, it) } }

    override suspend fun telemetryChunk(clientTripId: String, maxSamples: Int, maxEvents: Int): TelemetryChunk? {
        val (s, e) = telemetrySent[clientTripId] ?: (0L to 0L)
        val nextSamples = samples[clientTripId].orEmpty().filter { it.first > s }.take(maxSamples)
        val nextEvents = events[clientTripId].orEmpty().filter { it.first > e }.take(maxEvents)
        if (nextSamples.isEmpty() && nextEvents.isEmpty()) return null
        return TelemetryChunk(nextSamples.map { it.second }, nextEvents.map { it.second }, nextSamples.lastOrNull()?.first ?: s, nextEvents.lastOrNull()?.first ?: e)
    }

    override suspend fun markTelemetrySent(clientTripId: String, samplesUpTo: Long, eventsUpTo: Long) {
        telemetrySent[clientTripId] = samplesUpTo to eventsUpTo
    }

    override suspend fun markTelemetryDone(clientTripId: String, code: String?) { telemetryDone[clientTripId] = code }
    override suspend fun pendingTelemetryBatch() = pendingTelemetry
    override suspend fun setPendingTelemetryBatch(batch: PendingTelemetryBatch?) { pendingTelemetry = batch }
}
