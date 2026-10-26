package io.github.neotheon3.orangebox.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** A trip with its sync row (null before the first upload). */
data class TripWithSync(
    val id: Long,
    val clientTripId: String,
    val vehicleId: Long?,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val lastSampleAtMillis: Long,
    val serverId: String?,
    val serverUpdatedAt: String?,
    val uploadedBefore: Boolean?,
    val acceptedSnapshotAtMillis: Long?,
    val acceptedCompleted: Boolean?,
    val rejectedCode: String?,
    val forceUpload: Boolean?,
    val remoteOrigin: Boolean?,
)

data class VehicleWithSync(
    val id: Long,
    val clientVehicleId: String,
    val name: String,
    val maker: String?,
    val model: String?,
    val fuel: String,
    val displacementCc: Int?,
    val odometerKm: Int?,
    val serverId: String?,
    val goneOnServer: Boolean?,
)

data class TelemetryCandidate(val clientTripId: String, val vehicleServerId: String)

@Dao
interface SyncDao {
    @Query(
        """SELECT t.id, t.clientTripId, t.vehicleId, t.startedAtMillis, t.endedAtMillis, t.lastSampleAtMillis,
                  s.serverId, s.serverUpdatedAt, s.uploadedBefore, s.acceptedSnapshotAtMillis, s.acceptedCompleted,
                  s.rejectedCode, s.forceUpload, s.remoteOrigin
           FROM trips t LEFT JOIN trip_sync s ON s.tripId = t.id ORDER BY t.startedAtMillis""",
    )
    suspend fun tripsWithSync(): List<TripWithSync>

    @Query(
        """SELECT v.id, v.clientVehicleId, v.name, v.maker, v.model, v.fuel, v.displacementCc, v.odometerKm,
                  s.serverId, s.goneOnServer
           FROM vehicles v LEFT JOIN vehicle_sync s ON s.vehicleId = v.id ORDER BY v.id""",
    )
    suspend fun vehiclesWithSync(): List<VehicleWithSync>

    @Query("SELECT * FROM trips WHERE clientTripId = :clientTripId") suspend fun tripByClientId(clientTripId: String): TripEntity?
    @Query("SELECT * FROM trip_sync WHERE tripId = :tripId") suspend fun tripSync(tripId: Long): TripSyncEntity?
    @Upsert suspend fun upsertTripSync(row: TripSyncEntity)
    @Insert suspend fun insertTrip(trip: TripEntity): Long
    @androidx.room.Update suspend fun updateTrip(trip: TripEntity)
    @Query("DELETE FROM trips WHERE clientTripId = :clientTripId") suspend fun deleteTrip(clientTripId: String): Int

    @Query("SELECT * FROM vehicles WHERE clientVehicleId = :clientVehicleId") suspend fun vehicleByClientId(clientVehicleId: String): VehicleEntity?
    @Query("SELECT * FROM vehicle_sync WHERE vehicleId = :vehicleId") suspend fun vehicleSync(vehicleId: Long): VehicleSyncEntity?
    @Query("SELECT vehicleId FROM vehicle_sync WHERE serverId = :serverId") suspend fun vehicleIdByServerId(serverId: String): Long?
    @Upsert suspend fun upsertVehicleSync(row: VehicleSyncEntity)
    @Query("DELETE FROM vehicles WHERE id = :id") suspend fun deleteVehicle(id: Long)
    @Query("SELECT clientTripId FROM trips WHERE vehicleId = :vehicleId") suspend fun tripClientIdsOfVehicle(vehicleId: Long): List<String>

    @Query("SELECT * FROM local_trip_deletions") suspend fun localDeletions(): List<LocalTripDeletionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLocalDeletion(row: LocalTripDeletionEntity)
    @Query("UPDATE local_trip_deletions SET pushed = 1 WHERE clientTripId = :clientTripId") suspend fun markDeletionPushed(clientTripId: String)

    /** Finished trips recorded here whose summary the server accepted and whose vehicle it knows (telemetry, #67). */
    @Query(
        """SELECT t.clientTripId, vs.serverId AS vehicleServerId FROM trips t
           JOIN trip_sync s ON s.tripId = t.id
           JOIN vehicle_sync vs ON vs.vehicleId = t.vehicleId
           WHERE t.endedAtMillis IS NOT NULL AND s.uploadedBefore = 1 AND s.remoteOrigin = 0
             AND vs.serverId IS NOT NULL AND vs.goneOnServer = 0 AND t.startedAtMillis >= :notBeforeMillis
           ORDER BY t.startedAtMillis""",
    )
    suspend fun telemetryCandidates(notBeforeMillis: Long): List<TelemetryCandidate>

    @Query(
        """SELECT sm.* FROM trip_samples sm JOIN trips t ON t.id = sm.tripId
           WHERE t.clientTripId = :clientTripId AND sm.id > :afterId ORDER BY sm.id LIMIT :limit""",
    )
    suspend fun samplesAfter(clientTripId: String, afterId: Long, limit: Int): List<TripSampleEntity>

    @Query(
        """SELECT e.* FROM trip_events e JOIN trips t ON t.id = e.tripId
           WHERE t.clientTripId = :clientTripId AND e.id > :afterId ORDER BY e.id LIMIT :limit""",
    )
    suspend fun eventsAfter(clientTripId: String, afterId: Long, limit: Int): List<TripEventEntity>

    @Query("SELECT `key` FROM sync_state WHERE `key` LIKE :prefix || '%'") suspend fun stateKeys(prefix: String): List<String>

    @Query("SELECT value FROM sync_state WHERE `key` = :key") suspend fun state(key: String): String?

    /** The value of [key] as it changes (the last sync status for Settings). */
    @Query("SELECT value FROM sync_state WHERE `key` = :key") fun observeState(key: String): kotlinx.coroutines.flow.Flow<String?>
    @Upsert suspend fun putState(row: SyncStateEntity)
    @Query("DELETE FROM sync_state WHERE `key` = :key") suspend fun deleteState(key: String)

    @Query("DELETE FROM trips WHERE id IN (SELECT tripId FROM trip_sync WHERE remoteOrigin = 1)") suspend fun deleteRemoteTrips()
    @Query("DELETE FROM trip_sync") suspend fun clearTripSync()
    @Query("DELETE FROM vehicle_sync") suspend fun clearVehicleSync()
    @Query("DELETE FROM local_trip_deletions") suspend fun clearLocalDeletions()
    @Query("DELETE FROM sync_state") suspend fun clearSyncState()

    /**
     * Sign-out: forget everything this device knew about the server account (tokens, ids, queued uploads) and
     * the trips pulled from it, so another account starts clean. Trips recorded here are kept and upload again
     * to whoever signs in next.
     */
    @Transaction
    suspend fun forgetServer() {
        deleteRemoteTrips()
        clearTripSync()
        clearVehicleSync()
        clearLocalDeletions()
        clearSyncState()
    }

    /** Deletes a trip on this device and remembers it for the sync (`DELETE /v1/trips/{id}`, never re-uploaded). */
    @Transaction
    suspend fun deleteTripHere(clientTripId: String, nowMillis: Long) {
        val trip = tripByClientId(clientTripId) ?: return
        val serverId = tripSync(trip.id)?.serverId
        insertLocalDeletion(LocalTripDeletionEntity(clientTripId, serverId, nowMillis))
        deleteTrip(clientTripId)
    }
}
