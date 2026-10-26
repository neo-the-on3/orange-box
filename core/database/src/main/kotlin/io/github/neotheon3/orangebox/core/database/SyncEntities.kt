package io.github.neotheon3.orangebox.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Backend sync state (schema 5, M9). Kept out of `trips` / `vehicles` so the trip recorder's and the vehicle
 * editor's whole-row updates can never overwrite what the sync wrote. Only used in Remote / Fake mode.
 */

/** Server-side state of one trip. No row means "never uploaded, nothing known". */
@Entity(
    tableName = "trip_sync",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["serverId"], unique = true)],
)
data class TripSyncEntity(
    @PrimaryKey val tripId: Long,
    val serverId: String? = null,
    /** Raw wire `updatedAt` of the last version seen; parsed as an Instant to compare. */
    val serverUpdatedAt: String? = null,
    val uploadedBefore: Boolean = false,
    /** `snapshotAt` (epoch millis) of the last accepted upload. */
    val acceptedSnapshotAtMillis: Long? = null,
    val acceptedCompleted: Boolean = false,
    /** Problem code of a non-retryable `rejected` item; cleared when the trip is re-queued. */
    val rejectedCode: String? = null,
    /** Re-upload even though [acceptedSnapshotAtMillis] is current (`deployment_changed` recovery). */
    val forceUpload: Boolean = false,
    /** Pulled from the server (recorded on another device); never uploaded from here. */
    val remoteOrigin: Boolean = false,
)

@Entity(
    tableName = "vehicle_sync",
    foreignKeys = [ForeignKey(entity = VehicleEntity::class, parentColumns = ["id"], childColumns = ["vehicleId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["serverId"], unique = true)],
)
data class VehicleSyncEntity(
    @PrimaryKey val vehicleId: Long,
    val serverId: String? = null,
    /** `410 vehicle_deleted`: deleted on another device; its trips aren't uploaded. */
    val goneOnServer: Boolean = false,
)

/** A trip the user deleted on this device: queues `DELETE /v1/trips/{id}`, blocks pulls and re-uploads. */
@Entity(tableName = "local_trip_deletions")
data class LocalTripDeletionEntity(
    @PrimaryKey val clientTripId: String,
    val serverId: String?,
    val deletedAtMillis: Long,
    val pushed: Boolean = false,
)

/** Small key/value state: the opaque sync token, the pending trip batch, the last run's status. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)
