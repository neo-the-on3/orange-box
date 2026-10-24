package io.github.neotheon3.orangebox.feature.trips.charts

import io.github.neotheon3.orangebox.core.database.TripSampleEntity

/** Seconds spent in each speed zone of one trip (same zones as the telemetry engine's live speed zones). */
data class SpeedZones(val bounds: List<Double>, val seconds: List<Double>) {
    val totalS: Double get() = seconds.sum()

    /** Share of [totalS] per zone, 0..1 (all 0 for an empty trip). */
    val shares: List<Double> get() = totalS.let { t -> seconds.map { if (t > 0) it / t else 0.0 } }
}

/** One point of a time series: seconds since the trip start, value. */
data class SeriesPoint(val t: Double, val value: Double)

/**
 * Chart data derived from a trip's ~1 Hz samples. Each sample's reading holds until the next sample, but
 * a gap longer than [MAX_GAP_S] (link loss, poll stall) counts only [MAX_GAP_S], like the telemetry engine,
 * which never integrates across gaps.
 */
object TripSeries {
    /** Below this the car is stationary (TelemetryConfig.stationaryKph). */
    const val STATIONARY_KPH = 1.0
    /** Zone upper bounds in km/h (TelemetryConfig.speedZones); the last zone is open-ended. */
    val SPEED_ZONE_BOUNDS = listOf(30.0, 60.0, 90.0, 120.0)
    const val MAX_GAP_S = 5.0
    /** Economy is averaged per bucket of this many seconds. */
    const val ECONOMY_BUCKET_S = 60.0
    /** Buckets that burned less than this many litres or moved less than this many km are left out. */
    private const val MIN_BUCKET_FUEL_L = 0.002
    private const val MIN_BUCKET_KM = 0.01
    private const val MAX_ECONOMY = 99.9

    /** Zone index: 0 stationary, then one per bound, then above the last bound. */
    fun zoneOf(speedKph: Double): Int {
        if (speedKph < STATIONARY_KPH) return 0
        val i = SPEED_ZONE_BOUNDS.indexOfFirst { speedKph <= it }
        return if (i < 0) SPEED_ZONE_BOUNDS.size + 1 else i + 1
    }

    fun speedZones(samples: List<TripSampleEntity>): SpeedZones {
        val seconds = DoubleArray(SPEED_ZONE_BOUNDS.size + 2)
        forEachInterval(samples) { s, dt -> s.speedKph?.let { seconds[zoneOf(it)] += dt } }
        return SpeedZones(SPEED_ZONE_BOUNDS, seconds.toList())
    }

    /**
     * km/L per [ECONOMY_BUCKET_S] bucket (distance from speed, fuel from fuel flow), plotted at each bucket's
     * middle. Idle or coasting buckets with almost no fuel or distance are skipped, so the line doesn't spike.
     */
    fun economy(samples: List<TripSampleEntity>, startMillis: Long): List<SeriesPoint> {
        val km = sortedMapOf<Int, Double>()
        val litres = sortedMapOf<Int, Double>()
        forEachInterval(samples) { s, dt ->
            val bucket = (((s.timestampMillis - startMillis) / 1000.0) / ECONOMY_BUCKET_S).toInt().coerceAtLeast(0)
            s.speedKph?.let { km[bucket] = (km[bucket] ?: 0.0) + it * dt / 3600.0 }
            s.fuelFlowLph?.let { litres[bucket] = (litres[bucket] ?: 0.0) + it * dt / 3600.0 }
        }
        return km.mapNotNull { (b, d) ->
            val l = litres[b] ?: return@mapNotNull null
            if (l < MIN_BUCKET_FUEL_L || d < MIN_BUCKET_KM) null
            else SeriesPoint((b + 0.5) * ECONOMY_BUCKET_S, (d / l).coerceAtMost(MAX_ECONOMY))
        }
    }

    private inline fun forEachInterval(samples: List<TripSampleEntity>, block: (TripSampleEntity, Double) -> Unit) {
        val sorted = samples.sortedBy { it.timestampMillis }
        for (i in 0 until sorted.size - 1) {
            val dt = ((sorted[i + 1].timestampMillis - sorted[i].timestampMillis) / 1000.0).coerceIn(0.0, MAX_GAP_S)
            if (dt > 0) block(sorted[i], dt)
        }
    }
}
