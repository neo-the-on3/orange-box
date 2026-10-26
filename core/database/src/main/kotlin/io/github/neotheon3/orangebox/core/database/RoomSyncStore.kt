package io.github.neotheon3.orangebox.core.database

import io.github.neotheon3.orangebox.core.model.VehicleFuel
import io.github.neotheon3.orangebox.core.network.BackendJson
import io.github.neotheon3.orangebox.core.network.TripDto
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.network.UtcInstantSerializer
import io.github.neotheon3.orangebox.core.network.VehicleCreateDto
import io.github.neotheon3.orangebox.core.sync.LocalDeletion
import io.github.neotheon3.orangebox.core.sync.PendingTripBatch
import io.github.neotheon3.orangebox.core.sync.SyncStore
import io.github.neotheon3.orangebox.core.sync.SyncTrip
import io.github.neotheon3.orangebox.core.sync.SyncVehicle
import kotlinx.serialization.Serializable
import java.time.Instant
import javax.inject.Inject
import kotlin.math.roundToLong

/** [SyncStore] over Room (schema 5). Behaves like the engine tests' InMemorySyncStore. */
class RoomSyncStore @Inject constructor(private val dao: SyncDao) : SyncStore {

    override suspend fun since(): String? = dao.state(KEY_SINCE)
    override suspend fun storeSince(token: String) = dao.putState(SyncStateEntity(KEY_SINCE, token))
    override suspend fun clearSince() = dao.deleteState(KEY_SINCE)

    override suspend fun vehicles(): List<SyncVehicle> = dao.vehiclesWithSync().map { v ->
        SyncVehicle(
            clientVehicleId = v.clientVehicleId,
            serverId = v.serverId,
            create = VehicleCreateDto(
                clientVehicleId = v.clientVehicleId,
                // The spec requires make and model; a vehicle saved with only a name sends the name as the model.
                make = v.maker?.takeIf { it.isNotBlank() } ?: UNKNOWN_MAKE,
                model = v.model?.takeIf { it.isNotBlank() } ?: v.name,
                fuelType = wireFuel(v.fuel),
                nickname = v.name.take(40),
                engineDisplacementCc = v.displacementCc,
                odometerKm = v.odometerKm,
            ),
            goneOnServer = v.goneOnServer == true,
        )
    }

    override suspend fun setVehicleServerId(clientVehicleId: String, serverId: String?) {
        val v = dao.vehicleByClientId(clientVehicleId) ?: return
        val row = dao.vehicleSync(v.id) ?: VehicleSyncEntity(v.id)
        dao.upsertVehicleSync(row.copy(serverId = serverId, goneOnServer = false))
    }

    override suspend fun markVehicleGone(clientVehicleId: String) {
        val v = dao.vehicleByClientId(clientVehicleId) ?: return
        dao.upsertVehicleSync((dao.vehicleSync(v.id) ?: VehicleSyncEntity(v.id)).copy(serverId = null, goneOnServer = true))
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
            )
        }
    }

    private fun needsUpload(t: TripWithSync): Boolean {
        if (t.remoteOrigin == true || t.rejectedCode != null) return false
        if (t.forceUpload == true) return true
        val accepted = t.acceptedSnapshotAtMillis ?: return true
        return t.lastSampleAtMillis > accepted || (t.endedAtMillis != null && t.acceptedCompleted != true)
    }

    override suspend fun applyRemoteTrip(trip: TripDto) {
        val local = dao.tripByClientId(trip.clientTripId)
        val vehicleId = dao.vehicleIdByServerId(trip.vehicleId)
        val tripId = if (local == null) {
            dao.insertTrip(remoteToEntity(trip, vehicleId))
        } else {
            val sync = dao.tripSync(local.id)
            // Our own trips keep their local summary (they have the samples); pulled ones take the newer copy.
            if (sync?.remoteOrigin == true) dao.updateTrip(remoteToEntity(trip, vehicleId).copy(id = local.id))
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
        endReason = if (t.endedAt != null) END_REMOTE else null,
        durationS = t.durationS?.toDouble() ?: 0.0,
        distanceKm = t.distanceKm ?: 0.0,
        fuelLitres = t.fuelUsedL ?: 0.0,
        idleS = t.idleTimeS ?: 0.0,
        fuelCutS = t.fuelCutTimeS ?: 0.0,
        ecoS = t.ecoSpeedTimeS ?: 0.0,
        overspeedS = t.overSpeedTimeS ?: 0.0,
        harshAccelCount = t.harshAccelCount ?: 0,
        harshBrakeCount = t.harshBrakeCount ?: 0,
        maxSpeedKph = t.maxSpeedKmh ?: 0.0,
    )

    override suspend fun deleteTripLocally(clientTripId: String) {
        dao.deleteTrip(clientTripId)
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

    companion object {
        const val KEY_SINCE = "trips_since"
        const val KEY_PENDING_BATCH = "pending_trip_batch"
        const val END_REMOTE = "remote"
        const val UNKNOWN_MAKE = "Unknown"

        /** Orange Box fuel → spec `FuelType`. CNG and ethanol have no spec value yet (question for the backend). */
        fun wireFuel(fuel: String): String = when (VehicleFuel.entries.firstOrNull { it.name == fuel }) {
            VehicleFuel.PETROL, null -> "gasoline"
            VehicleFuel.DIESEL -> "diesel"
            VehicleFuel.LPG -> "lpg"
            VehicleFuel.CNG, VehicleFuel.ETHANOL -> "other"
        }
    }
}
