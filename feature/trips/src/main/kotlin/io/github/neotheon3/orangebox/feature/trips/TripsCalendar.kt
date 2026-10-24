package io.github.neotheon3.orangebox.feature.trips

import io.github.neotheon3.orangebox.core.database.TripEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One cell of the month grid. Leading/trailing days of the neighbouring months have [inMonth] false. */
data class CalendarDay(val date: LocalDate, val inMonth: Boolean, val tripCount: Int, val isToday: Boolean)

/** A trip row in the history list, already in local time. */
data class TripListItem(
    val id: Long,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    val distanceKm: Double,
    val durationS: Double,
    val ecoS: Double,
    val economyKmPerL: Double?,
    val harshEvents: Int,
    val overspeedEvents: Int,
) {
    val recording: Boolean get() = end == null
}

data class MonthSummary(val trips: Int, val distanceKm: Double, val durationS: Double)

data class TripsUiState(
    val month: YearMonth,
    val firstDayOfWeek: DayOfWeek,
    /** Whole weeks covering [month] (4 to 6 rows of 7). */
    val days: List<CalendarDay>,
    val selectedDay: LocalDate?,
    /** The selected day's trips, or the whole month's when no day is selected; newest first. */
    val trips: List<TripListItem>,
    val summary: MonthSummary,
    val loading: Boolean = false,
) {
    val weeks: List<List<CalendarDay>> get() = days.chunked(7)
    /** [trips] grouped by local start day, newest day first (order preserved inside a day). */
    val tripsByDay: List<Pair<LocalDate, List<TripListItem>>> get() =
        trips.groupBy { it.start.toLocalDate() }.toList()
    val isEmptyMonth: Boolean get() = !loading && summary.trips == 0
}

/** Pure calendar logic, kept out of the ViewModel so it is unit-tested directly. */
object TripsCalendar {
    fun localStart(trip: TripEntity, zone: ZoneId): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(trip.startedAtMillis), zone)

    fun item(trip: TripEntity, zone: ZoneId) = TripListItem(
        id = trip.id,
        start = localStart(trip, zone),
        end = trip.endedAtMillis?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) },
        distanceKm = trip.distanceKm,
        durationS = trip.durationS,
        ecoS = trip.ecoS,
        economyKmPerL = TripFormat.economyKmPerL(trip),
        harshEvents = trip.harshAccelCount + trip.harshBrakeCount,
        overspeedEvents = trip.overspeedCount,
    )

    /** Trips per local start day. */
    fun tripsPerDay(trips: List<TripEntity>, zone: ZoneId): Map<LocalDate, Int> =
        trips.groupingBy { localStart(it, zone).toLocalDate() }.eachCount()

    fun grid(month: YearMonth, firstDayOfWeek: DayOfWeek, perDay: Map<LocalDate, Int>, today: LocalDate): List<CalendarDay> {
        val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        val last = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(firstDayOfWeek.minus(1)))
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
            .map { CalendarDay(it, YearMonth.from(it) == month, perDay[it] ?: 0, it == today) }
            .toList()
    }

    fun state(
        trips: List<TripEntity>,
        month: YearMonth,
        selectedDay: LocalDate?,
        zone: ZoneId,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
    ): TripsUiState {
        val inMonth = trips.filter { YearMonth.from(localStart(it, zone)) == month }
            .sortedByDescending { it.startedAtMillis }
        val day = selectedDay?.takeIf { YearMonth.from(it) == month }
        val shown = if (day == null) inMonth else inMonth.filter { localStart(it, zone).toLocalDate() == day }
        return TripsUiState(
            month = month,
            firstDayOfWeek = firstDayOfWeek,
            days = grid(month, firstDayOfWeek, tripsPerDay(inMonth, zone), today),
            selectedDay = day,
            trips = shown.map { item(it, zone) },
            summary = MonthSummary(inMonth.size, inMonth.sumOf { it.distanceKm }, inMonth.sumOf { it.durationS }),
        )
    }
}
