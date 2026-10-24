package io.github.neotheon3.orangebox.feature.trips

import io.github.neotheon3.orangebox.core.database.TripEntity
import java.util.Locale
import kotlin.math.roundToLong

/** Shared number formats for the trip screens (km and L until the units setting lands in M8). */
object TripFormat {
    /** H:MM:SS, e.g. 0:32:05 (the dashboard's TRIP tile format). */
    fun elapsed(seconds: Double): String {
        val s = seconds.roundToLong().coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }

    fun km(km: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", km)

    fun decimal(v: Double, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f", v)

    /** km/L, or null when the trip burned (almost) no fuel or barely moved: nothing meaningful to show. */
    fun economyKmPerL(distanceKm: Double, fuelLitres: Double): Double? =
        if (fuelLitres < MIN_FUEL_L || distanceKm < MIN_DISTANCE_KM) null else (distanceKm / fuelLitres).coerceAtMost(MAX_ECONOMY)

    fun economyKmPerL(trip: TripEntity): Double? = economyKmPerL(trip.distanceKm, trip.fuelLitres)

    private const val MIN_FUEL_L = 0.01
    private const val MIN_DISTANCE_KM = 0.05
    /** Same cap as the dashboard's FUEL card. */
    private const val MAX_ECONOMY = 99.9
}
