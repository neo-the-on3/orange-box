package io.github.neotheon3.orangebox.feature.trips.stats

import io.github.neotheon3.orangebox.core.database.TripPeriodTotals
import io.github.neotheon3.orangebox.feature.trips.TripFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class StatsPeriod { WEEK, MONTH }

/** One week or month: [start] inclusive, [end] exclusive, local dates. */
data class PeriodRange(val start: LocalDate, val end: LocalDate) {
    fun fromMillis(zone: ZoneId) = start.atStartOfDay(zone).toInstant().toEpochMilli()
    fun toMillis(zone: ZoneId) = end.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** Totals of one period plus the derived figures the screen shows. */
data class PeriodStats(val range: PeriodRange, val totals: TripPeriodTotals) {
    val economyKmPerL: Double? get() = TripFormat.economyKmPerL(totals.distanceKm, totals.fuelLitres)
    /** Share of driving time spent eco-driving, 0..1, or null without driving time. */
    val ecoShare: Double? get() = if (totals.durationS >= 1.0) (totals.ecoS / totals.durationS).coerceIn(0.0, 1.0) else null
    /** Harsh accel + brake per 100 km, or null under 1 km (too little to mean anything). */
    val harshPer100Km: Double? get() = if (totals.distanceKm >= 1.0) totals.harshEvents * 100.0 / totals.distanceKm else null
}

/** Local aggregate stats (#46): calendar weeks or months, newest first, from [TripPeriodTotals] queries. */
object TripStats {
    /**
     * The [count] periods ending with the one containing [today], newest first. Weeks start on
     * [firstDayOfWeek] (from the locale); months are calendar months.
     */
    fun ranges(period: StatsPeriod, today: LocalDate, count: Int, firstDayOfWeek: DayOfWeek): List<PeriodRange> = when (period) {
        StatsPeriod.WEEK -> {
            val start = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            List(count) { i -> start.minusWeeks(i.toLong()).let { PeriodRange(it, it.plusWeeks(1)) } }
        }
        StatsPeriod.MONTH -> {
            val month = YearMonth.from(today)
            List(count) { i -> month.minusMonths(i.toLong()).let { PeriodRange(it.atDay(1), it.plusMonths(1).atDay(1)) } }
        }
    }
}
