package io.github.neotheon3.orangebox.feature.trips

import io.github.neotheon3.orangebox.core.database.TripEntity
import java.time.LocalDateTime
import java.time.ZoneId

internal val TEST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

internal fun millis(t: LocalDateTime, zone: ZoneId = TEST_ZONE) = t.atZone(zone).toInstant().toEpochMilli()

/** A finished trip starting at [start] (local time in [TEST_ZONE]). */
internal fun trip(
    id: Long,
    start: LocalDateTime,
    minutes: Long = 30,
    km: Double = 12.0,
    litres: Double = 1.0,
    harshAccel: Int = 0,
    harshBrake: Int = 0,
    overspeed: Int = 0,
    open: Boolean = false,
) = TripEntity(
    id = id,
    vehicleId = null,
    startedAtMillis = millis(start),
    endedAtMillis = if (open) null else millis(start.plusMinutes(minutes)),
    lastSampleAtMillis = millis(start.plusMinutes(minutes)),
    endReason = if (open) null else "engine_off",
    durationS = minutes * 60.0,
    distanceKm = km,
    fuelLitres = litres,
    harshAccelCount = harshAccel,
    harshBrakeCount = harshBrake,
    overspeedCount = overspeed,
)
