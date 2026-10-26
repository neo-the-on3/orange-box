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
    val accountId: String? = null,
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
    val accountId: String? = null,
)

/** A trip that isn't the signed-in account's (recorded signed out, or another account's). */
data class ForeignTrip(val id: Long, val clientTripId: String, val vehicleId: Long?, val accountId: String?, val uploadedBefore: Boolean?)

data class TelemetryCandidate(val clientTripId: String, val vehicleServerId: String)

@Dao
interface SyncDao {
    @Query(
        """SELECT t.id, t.clientTripId, t.vehicleId, t.startedAtMillis, t.endedAtMillis, t.lastSampleAtMillis,
                  s.serverId, s.serverUpdatedAt, s.uploadedBefore, s.acceptedSnapshotAtMillis, s.acceptedCompleted,
                  s.rejectedCode, s.forceUpload, s.remoteOrigin, t.accountId
           FROM trips t LEFT JOIN trip_sync s ON s.tripId = t.id ORDER BY t.startedAtMillis""",
    )
    suspend fun tripsWithSync(): List<TripWithSync>

    @Query(
        """SELECT v.id, v.clientVehicleId, v.name, v.maker, v.model, v.fuel, v.displacementCc, v.odometerKm,
                  s.serverId, s.goneOnServer, v.accountId
           FROM vehicles v LEFT JOIN vehicle_sync s ON s.vehicleId = v.id ORDER BY v.id""",
    )
    suspend fun vehiclesWithSync(): List<VehicleWithSync>

    @Query("SELECT * FROM trips WHERE clientTripId = :clientTripId") suspend fun tripByClientId(clientTripId: String): TripEntity?
    @Query("SELECT * FROM trip_sync WHERE tripId = :tripId") suspend fun tripSync(tripId: Long): TripSyncEntity?
    @Upsert suspend fun upsertTripSync(row: TripSyncEntity)
    @Insert suspend fun insertTrip(trip: TripEntity): Long
    @androidx.room.Update suspend fun updateTrip(trip: TripEntity)
    @Query("DELETE FROM trips WHERE clientTripId = :clientTripId") suspend fun deleteTrip(clientTripId: String): Int

    @Query("SELECT clientVehicleId FROM vehicles WHERE id = :id") suspend fun clientVehicleIdOf(id: Long): String?
    @Query("SELECT * FROM vehicles WHERE clientVehicleId = :clientVehicleId") suspend fun vehicleByClientId(clientVehicleId: String): VehicleEntity?
    @Query("SELECT * FROM vehicle_sync WHERE vehicleId = :vehicleId") suspend fun vehicleSync(vehicleId: Long): VehicleSyncEntity?
    @Query("SELECT vehicleId FROM vehicle_sync WHERE serverId = :serverId") suspend fun vehicleIdByServerId(serverId: String): Long?
    @Upsert suspend fun upsertVehicleSync(row: VehicleSyncEntity)
    @androidx.room.Update suspend fun updateVehicle(vehicle: VehicleEntity)
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
             AND t.accountId IS :account
           ORDER BY t.startedAtMillis""",
    )
    suspend fun telemetryCandidates(notBeforeMillis: Long, account: String?): List<TelemetryCandidate>

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

    @Query("DELETE FROM vehicle_sync") suspend fun clearVehicleSync()
    @Query("DELETE FROM local_trip_deletions") suspend fun clearLocalDeletions()

    /** Everything in `sync_state` but per-trip telemetry progress, which belongs to the trip's own account. */
    @Query("DELETE FROM sync_state WHERE `key` NOT LIKE '" + RoomSyncStore.KEY_TELEMETRY + "%'") suspend fun clearSessionState()

    /**
     * Sign-out: forget the session's server state (the account, sync token, queued batches and DTC reports,
     * vehicle server ids, unpushed deletions). Trips keep their account stamp and its server ids (`trip_sync`,
     * telemetry progress): they're that account's, never uploaded by another one, and current again when it
     * signs back in. Nothing is deleted.
     */
    @Transaction
    suspend fun forgetServer() {
        clearVehicleSync()
        clearLocalDeletions()
        clearSessionState()
    }

    /**
     * Deletes a trip on this device and remembers it for the sync (`DELETE /v1/trips/{id}`, never re-uploaded).
     * Another account's trip is only deleted here: its server copy isn't this account's to delete.
     */
    @Transaction
    suspend fun deleteTripHere(clientTripId: String, nowMillis: Long) {
        val trip = tripByClientId(clientTripId) ?: return
        val own = trip.accountId == state(ACCOUNT_STATE_KEY)
        val serverId = tripSync(trip.id)?.serverId.takeIf { own }
        insertLocalDeletion(LocalTripDeletionEntity(clientTripId, serverId, nowMillis, pushed = !own))
        deleteTrip(clientTripId)
    }

    // --- accounts (Q13) ---

    @Query(
        """SELECT t.id, t.clientTripId, t.vehicleId, t.accountId, s.uploadedBefore FROM trips t
           LEFT JOIN trip_sync s ON s.tripId = t.id WHERE t.accountId IS NOT :account""",
    )
    suspend fun foreignTrips(account: String): List<ForeignTrip>

    @Query("SELECT COUNT(*) FROM trips WHERE accountId IS NOT :account")
    fun observeForeignTripCount(account: String): kotlinx.coroutines.flow.Flow<Int>

    @Query("UPDATE trips SET accountId = :account WHERE id = :id") suspend fun stampTrip(id: Long, account: String)
    @Query("UPDATE vehicles SET accountId = :account WHERE id = :id") suspend fun stampVehicle(id: Long, account: String)
    @Query("SELECT id FROM vehicles WHERE accountId IS NULL") suspend fun unassignedVehicles(): List<Long>
    @Query("DELETE FROM trip_sync WHERE tripId = :tripId") suspend fun deleteTripSync(tripId: Long)
    @Query("DELETE FROM trips WHERE id = :id") suspend fun deleteTripById(id: Long)
}
