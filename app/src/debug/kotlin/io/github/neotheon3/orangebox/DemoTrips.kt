package io.github.neotheon3.orangebox

import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import kotlinx.coroutines.flow.first
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Debug-only demo trips (`--es demo_trips seed`): the CI and API 25 emulators have no GPS fix, so this
 * gives Trips, trip detail (route map) and stats something to show. Marked with end reason [REASON];
 * seeding again is a no-op.
 */
object DemoTrips {
    const val REASON = "demo"

    suspend fun seed(repo: TripRepository, nowMillis: Long): Int {
        if (repo.trips.first().any { it.endReason == REASON }) return 0
        val day = 24 * 3600_000L
        insert(repo, nowMillis - 3600_000L, 25 * 60, located = true, events = true)
        insert(repo, nowMillis - 3 * 3600_000L, 18 * 60, located = false, events = false)
        insert(repo, nowMillis - 9 * day, 40 * 60, located = true, events = false)
        return 3
    }

    private suspend fun insert(repo: TripRepository, startMillis: Long, seconds: Int, located: Boolean, events: Boolean) {
        var trip = repo.start(TripEntity(vehicleId = null, startedAtMillis = startMillis, lastSampleAtMillis = startMillis))
        val samples = ArrayList<TripSampleEntity>(seconds)
        var km = 0.0
        var litres = 0.0
        var maxSpeed = 0.0
        var idle = 0.0
        var eco = 0.0
        for (i in 0 until seconds) {
            val a = i.toDouble() / seconds * 2 * Math.PI
            val speed = max(0.0, 45 + 40 * sin(a * 3)).let { if (i in seconds / 5..seconds / 5 + 60) 0.0 else it }
            val flow = 0.8 + speed / 25
            km += speed / 3600
            litres += flow / 3600
            maxSpeed = max(maxSpeed, speed)
            if (speed < 1) idle += 1.0 else if (speed < 70) eco += 1.0
            samples += TripSampleEntity(
                tripId = trip.id, timestampMillis = startMillis + i * 1000L, speedKph = speed, rpm = 800 + speed * 25,
                fuelFlowLph = flow, coolantC = 88.0,
                latitude = if (located) 12.9763 + 0.012 * sin(a) + 0.002 * sin(a * 5) else null,
                longitude = if (located) 77.5929 + 0.016 * cos(a) else null,
                accuracyM = if (located) 6f else null,
            )
        }
        fun at(i: Int) = samples[i]
        val ev = if (!events) emptyList() else listOf(
            TripEventEntity(tripId = trip.id, timestampMillis = at(seconds / 8).timestampMillis, type = "harsh_accel", value = 3.4, latitude = at(seconds / 8).latitude, longitude = at(seconds / 8).longitude),
            TripEventEntity(tripId = trip.id, timestampMillis = at(seconds / 2).timestampMillis, type = "harsh_brake", value = -4.2, latitude = at(seconds / 2).latitude, longitude = at(seconds / 2).longitude),
            TripEventEntity(tripId = trip.id, timestampMillis = at(seconds * 3 / 4).timestampMillis, type = "overspeed", value = 121.0, latitude = at(seconds * 3 / 4).latitude, longitude = at(seconds * 3 / 4).longitude),
        )
        val end = startMillis + seconds * 1000L
        trip = trip.copy(
            endedAtMillis = end, lastSampleAtMillis = end, endReason = REASON, durationS = seconds.toDouble(),
            distanceKm = km, fuelLitres = litres, co2Grams = litres * 2_320, idleS = idle, ecoS = eco,
            harshAccelCount = ev.count { it.type == "harsh_accel" }, harshBrakeCount = ev.count { it.type == "harsh_brake" },
            overspeedCount = ev.count { it.type == "overspeed" }, overspeedS = if (events) 12.0 else 0.0, maxSpeedKph = maxSpeed,
        )
        repo.checkpoint(trip, samples, ev)
    }
}
