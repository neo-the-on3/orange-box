package io.github.neotheon3.orangebox.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One recorded drive. [endedAtMillis] is null while recording; the summary columns are checkpointed
 * every few seconds, so a trip left open by process death keeps its last checkpoint and is closed by
 * [TripDao.closeOrphans] with [endReason] `process_death`.
 */
@Entity(tableName = "trips", indices = [Index("startedAtMillis"), Index(value = ["clientTripId"], unique = true)])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Backend `clientTripId` (UUID): set once when the trip starts, never changed (schema 5, M9). */
    @ColumnInfo(defaultValue = "''") val clientTripId: String = UUID.randomUUID().toString(),
    val vehicleId: Long?,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    /** Timestamp of the last sample written; the end time for orphans. */
    val lastSampleAtMillis: Long,
    /** engine_off, disconnected, user, process_death. */
    val endReason: String? = null,
    val durationS: Double = 0.0,
    val distanceKm: Double = 0.0,
    val fuelLitres: Double = 0.0,
    val co2Grams: Double = 0.0,
    val idleS: Double = 0.0,
    val fuelCutS: Double = 0.0,
    val ecoS: Double = 0.0,
    val overspeedS: Double = 0.0,
    val harshAccelCount: Int = 0,
    val harshBrakeCount: Int = 0,
    val overspeedCount: Int = 0,
    val maxSpeedKph: Double = 0.0,
    /**
     * The backend account that recorded or pulled the trip (the member id; schema 6). Null: recorded signed out
     * (or in Fake mode). Kept on sign-out; only that account syncs it. The recorder's checkpoints never change it.
     */
    val accountId: String? = null,
)

/** About one per second while recording. Location columns are null without a fix or permission. */
@Entity(
    tableName = "trip_samples",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId", "timestampMillis")],
)
data class TripSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestampMillis: Long,
    val speedKph: Double?,
    val rpm: Double?,
    val fuelFlowLph: Double?,
    val coolantC: Double?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Float? = null,
    /** Raw PID 5E (L/h), only when the car answered it (schema 6). Uploaded as `pids["0x5E"]`. */
    val fuelRatePidLph: Double? = null,
    /** Raw PID 10 (g/s), only when the car answered it (schema 6). Uploaded as `mafGps`. */
    val mafGps: Double? = null,
)

/**
 * Driving events: harsh_accel, harsh_brake, overspeed, fuel_cut, stamped at their start. [value] is the
 * peak while the event ran: acceleration in m/s² (negative for braking), max speed in km/h for overspeed,
 * the speed at the start for fuel cut. [durationS] is null for rows recorded before schema 3 (and their
 * [value] is the start sample, #164).
 */
@Entity(
    tableName = "trip_events",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId", "timestampMillis")],
)
data class TripEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Backend `clientEventId` (UUID): assigned when the event is recorded, sent unchanged on every upload. */
    @ColumnInfo(defaultValue = "''") val clientEventId: String = UUID.randomUUID().toString(),
    val tripId: Long,
    val timestampMillis: Long,
    val type: String,
    val value: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val durationS: Double? = null,
)

/**
 * Totals of the finished trips that started in a time range ([TripDao.totals]): the stats screen's weekly
 * and monthly figures (#46). Plain query result, not a table.
 */
data class TripPeriodTotals(
    val trips: Int = 0,
    val distanceKm: Double = 0.0,
    val durationS: Double = 0.0,
    val fuelLitres: Double = 0.0,
    val ecoS: Double = 0.0,
    val idleS: Double = 0.0,
    val harshAccel: Int = 0,
    val harshBrake: Int = 0,
    val overspeed: Int = 0,
    val maxSpeedKph: Double = 0.0,
) {
    val harshEvents: Int get() = harshAccel + harshBrake

    operator fun plus(t: TripEntity) = TripPeriodTotals(
        trips + 1, distanceKm + t.distanceKm, durationS + t.durationS, fuelLitres + t.fuelLitres, ecoS + t.ecoS, idleS + t.idleS,
        harshAccel + t.harshAccelCount, harshBrake + t.harshBrakeCount, overspeed + t.overspeedCount, maxOf(maxSpeedKph, t.maxSpeedKph),
    )
}
