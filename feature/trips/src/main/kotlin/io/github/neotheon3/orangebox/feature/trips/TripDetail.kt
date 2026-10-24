package io.github.neotheon3.orangebox.feature.trips

import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import io.github.neotheon3.orangebox.feature.trips.charts.SeriesPoint
import io.github.neotheon3.orangebox.feature.trips.charts.SpeedZones
import io.github.neotheon3.orangebox.feature.trips.charts.TripSeries
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

/** [value] is the event's peak ([TripEventEntity.value]); [durationS] is null for events recorded before #164. */
data class TripEventItem(val type: TripEventType, val time: LocalDateTime, val value: Double?, val position: GeoPoint?, val durationS: Double? = null)

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
    /**
     * Driving events: harsh acceleration, harsh braking and overspeed. The trip list's "N events" counts
     * exactly these (#165).
     */
    val events: List<TripEventItem>,
    /**
     * Fuel-cut (engine-braking) periods. They save fuel rather than mark bad driving, so they get their own
     * section and are never counted as events (#165).
     */
    val fuelCuts: List<TripEventItem>,
    /** (seconds since the trip start, km/h) at about 1 Hz, for the charts. */
    val speedSeries: List<Pair<Double, Double>>,
    /** Seconds per speed zone (#45). */
    val speedZones: SpeedZones = SpeedZones(TripSeries.SPEED_ZONE_BOUNDS, List(TripSeries.SPEED_ZONE_BOUNDS.size + 2) { 0.0 }),
    /** km/L per minute (#45). */
    val economySeries: List<SeriesPoint> = emptyList(),
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
        val items = events.sortedBy { it.timestampMillis }.mapNotNull { e ->
            val type = TripEventType.of(e.type) ?: return@mapNotNull null
            TripEventItem(
                type = type,
                time = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.timestampMillis), zone),
                value = e.value,
                position = if (e.latitude != null && e.longitude != null) GeoPoint(e.latitude!!, e.longitude!!) else null,
                durationS = e.durationS,
            )
        }
        return TripDetail(
            header = TripsCalendar.item(trip, zone),
            metrics = metrics(trip),
            route = route(sorted),
            events = items.filter { it.type != TripEventType.FUEL_CUT },
            fuelCuts = items.filter { it.type == TripEventType.FUEL_CUT },
            speedSeries = sorted.mapNotNull { s -> s.speedKph?.let { (s.timestampMillis - trip.startedAtMillis) / 1000.0 to it } },
            speedZones = TripSeries.speedZones(sorted),
            economySeries = TripSeries.economy(sorted, trip.startedAtMillis),
        )
    }
}
