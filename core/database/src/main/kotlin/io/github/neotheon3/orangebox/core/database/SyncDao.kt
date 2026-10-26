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

    @Query("SELECT value FROM sync_state WHERE `key` = :key") suspend fun state(key: String): String?
    @Upsert suspend fun putState(row: SyncStateEntity)
    @Query("DELETE FROM sync_state WHERE `key` = :key") suspend fun deleteState(key: String)

    /** Deletes a trip on this device and remembers it for the sync (`DELETE /v1/trips/{id}`, never re-uploaded). */
    @Transaction
    suspend fun deleteTripHere(clientTripId: String, nowMillis: Long) {
        val trip = tripByClientId(clientTripId) ?: return
        val serverId = tripSync(trip.id)?.serverId
        insertLocalDeletion(LocalTripDeletionEntity(clientTripId, serverId, nowMillis))
        deleteTrip(clientTripId)
    }
}
