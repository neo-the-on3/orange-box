package io.github.neotheon3.orangebox.feature.trips

import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import io.github.neotheon3.orangebox.feature.trips.map.GeoPoint
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

enum class TripEventType(val wire: String) {
    HARSH_ACCEL("harsh_accel"), HARSH_BRAKE("harsh_brake"), OVERSPEED("overspeed"), FUEL_CUT("fuel_cut");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

data class TripEventItem(val type: TripEventType, val time: LocalDateTime, val value: Double?, val position: GeoPoint?)

/** Summary metrics of one trip. Averages are null when they would be meaningless (no time or no fuel). */
data class TripMetrics(
    val distanceKm: Double,
    val durationS: Double,
    val avgSpeedKph: Double?,
    val maxSpeedKph: Double,
    val fuelLitres: Double,
    val economyKmPerL: Double?,
    val co2Kg: Double,
    val idleS: Double,
    val ecoS: Double,
    val overspeedS: Double,
    val fuelCutS: Double,
    val harshAccel: Int,
    val harshBrake: Int,
    val overspeedCount: Int,
)

data class TripDetail(
    val header: TripListItem,
    val metrics: TripMetrics,
    /** Located samples in time order; fewer than 2 means there is no route to draw. */
    val route: List<GeoPoint>,
    val events: List<TripEventItem>,
    /** (seconds since the trip start, km/h) at about 1 Hz, for the charts. */
    val speedSeries: List<Pair<Double, Double>>,
) {
    val hasRoute: Boolean get() = route.size >= 2
}

object TripDetails {
    /** Fixes worse than this are dropped from the route (they zig-zag across streets). */
    const val MAX_ACCURACY_M = 75f

    fun metrics(t: TripEntity) = TripMetrics(
        distanceKm = t.distanceKm,
        durationS = t.durationS,
        avgSpeedKph = if (t.durationS >= 1.0) t.distanceKm / (t.durationS / 3600.0) else null,
        maxSpeedKph = t.maxSpeedKph,
        fuelLitres = t.fuelLitres,
        economyKmPerL = TripFormat.economyKmPerL(t),
        co2Kg = t.co2Grams / 1000.0,
        idleS = t.idleS,
        ecoS = t.ecoS,
        overspeedS = t.overspeedS,
        fuelCutS = t.fuelCutS,
        harshAccel = t.harshAccelCount,
        harshBrake = t.harshBrakeCount,
        overspeedCount = t.overspeedCount,
    )

    fun route(samples: List<TripSampleEntity>): List<GeoPoint> = samples
        .asSequence()
        .filter { it.latitude != null && it.longitude != null }
        .filter { (it.accuracyM ?: 0f) <= MAX_ACCURACY_M }
        .filter { it.latitude!! in -90.0..90.0 && it.longitude!! in -180.0..180.0 && !(it.latitude == 0.0 && it.longitude == 0.0) }
        .map { GeoPoint(it.latitude!!, it.longitude!!) }
        .toList()

    fun detail(trip: TripEntity, samples: List<TripSampleEntity>, events: List<TripEventEntity>, zone: ZoneId): TripDetail {
        val sorted = samples.sortedBy { it.timestampMillis }
        return TripDetail(
            header = TripsCalendar.item(trip, zone),
            metrics = metrics(trip),
            route = route(sorted),
            events = events.sortedBy { it.timestampMillis }.mapNotNull { e ->
                val type = TripEventType.of(e.type) ?: return@mapNotNull null
                TripEventItem(
                    type = type,
                    time = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.timestampMillis), zone),
                    value = e.value,
                    position = if (e.latitude != null && e.longitude != null) GeoPoint(e.latitude!!, e.longitude!!) else null,
                )
            },
            speedSeries = sorted.mapNotNull { s -> s.speedKph?.let { (s.timestampMillis - trip.startedAtMillis) / 1000.0 to it } },
        )
    }
}
