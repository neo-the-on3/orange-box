package io.github.neotheon3.orangebox.feature.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

class TripsCalendarTest {
    private val oct = YearMonth.of(2026, 10)
    private val today = LocalDate.of(2026, 10, 24)

    @Test fun `grid covers whole weeks from the locale's first day`() {
        // 1 Oct 2026 is a Thursday.
        val monday = TripsCalendar.grid(oct, DayOfWeek.MONDAY, emptyMap(), today)
        assertEquals(LocalDate.of(2026, 9, 28), monday.first().date)
        assertEquals(LocalDate.of(2026, 11, 1), monday.last().date)
        assertEquals(35, monday.size)
        val sunday = TripsCalendar.grid(oct, DayOfWeek.SUNDAY, emptyMap(), today)
        assertEquals(LocalDate.of(2026, 9, 27), sunday.first().date)
        assertEquals(LocalDate.of(2026, 10, 31), sunday.last().date)
        assertEquals(31, sunday.count { it.inMonth })
        assertTrue(sunday.single { it.date == today }.isToday)
    }

    @Test fun `a month needing six rows gets six`() {
        // Aug 2026 starts on a Saturday: Monday-first needs 6 weeks.
        assertEquals(42, TripsCalendar.grid(YearMonth.of(2026, 8), DayOfWeek.MONDAY, emptyMap(), today).size)
        // Feb 2027 starts on a Monday and has 28 days: exactly 4 weeks.
        assertEquals(28, TripsCalendar.grid(YearMonth.of(2027, 2), DayOfWeek.MONDAY, emptyMap(), today).size)
    }

    @Test fun `days are marked with their trip count in local time`() {
        val trips = listOf(
            trip(1, LocalDateTime.of(2026, 10, 3, 8, 0)),
            trip(2, LocalDateTime.of(2026, 10, 3, 18, 0)),
            // 00:15 IST on the 5th is still the 4th in UTC: the local zone decides.
            trip(3, LocalDateTime.of(2026, 10, 5, 0, 15)),
            trip(4, LocalDateTime.of(2026, 9, 30, 9, 0)),
        )
        val s = TripsCalendar.state(trips, oct, null, TEST_ZONE, today, DayOfWeek.MONDAY)
        assertEquals(2, s.days.single { it.date == LocalDate.of(2026, 10, 3) }.tripCount)
        assertEquals(1, s.days.single { it.date == LocalDate.of(2026, 10, 5) }.tripCount)
        assertEquals("September trips are not counted on the leading cells", 0, s.days.single { it.date == LocalDate.of(2026, 9, 30) }.tripCount)
        assertEquals(listOf(3L, 2L, 1L), s.trips.map { it.id })
        assertEquals(3, s.summary.trips)
        assertEquals(36.0, s.summary.distanceKm, 1e-9)
        val utc = TripsCalendar.state(trips, oct, null, ZoneOffset.UTC, today, DayOfWeek.MONDAY)
        assertEquals(1, utc.days.single { it.date == LocalDate.of(2026, 10, 4) }.tripCount)
    }

    @Test fun `an excluded trip stays listed and marked but leaves the month summary (#211)`() {
        val trips = listOf(
            trip(1, LocalDateTime.of(2026, 10, 3, 8, 0), km = 10.0),
            trip(2, LocalDateTime.of(2026, 10, 4, 8, 0), km = 90.0).copy(excludedFromStats = true),
        )
        val s = TripsCalendar.state(trips, oct, null, TEST_ZONE, today, DayOfWeek.MONDAY)
        assertEquals(listOf(2L, 1L), s.trips.map { it.id })
        assertTrue(s.trips.first().excludedFromStats)
        assertEquals(1, s.summary.trips)
        assertEquals(10.0, s.summary.distanceKm, 1e-9)
    }

    @Test fun `selecting a day filters the list, other months ignore the selection`() {
        val trips = listOf(trip(1, LocalDateTime.of(2026, 10, 3, 8, 0)), trip(2, LocalDateTime.of(2026, 10, 9, 8, 0)))
        val s = TripsCalendar.state(trips, oct, LocalDate.of(2026, 10, 9), TEST_ZONE, today, DayOfWeek.MONDAY)
        assertEquals(listOf(2L), s.trips.map { it.id })
        assertEquals("summary stays the month's", 2, s.summary.trips)
        val other = TripsCalendar.state(trips, oct, LocalDate.of(2026, 11, 2), TEST_ZONE, today, DayOfWeek.MONDAY)
        assertNull(other.selectedDay)
        assertEquals(2, other.trips.size)
    }

    @Test fun `empty month`() {
        val s = TripsCalendar.state(listOf(trip(1, LocalDateTime.of(2026, 9, 3, 8, 0))), oct, null, TEST_ZONE, today, DayOfWeek.MONDAY)
        assertTrue(s.isEmptyMonth)
        assertTrue(s.trips.isEmpty())
        assertTrue(s.days.all { it.tripCount == 0 })
    }

    @Test fun `list items carry economy, events and recording state`() {
        val s = TripsCalendar.state(
            listOf(
                trip(1, LocalDateTime.of(2026, 10, 3, 8, 0), km = 15.0, litres = 1.0, harshAccel = 1, harshBrake = 2, overspeed = 1),
                trip(2, LocalDateTime.of(2026, 10, 4, 8, 0), km = 0.0, litres = 0.0, open = true),
            ),
            oct, null, TEST_ZONE, today, DayOfWeek.MONDAY,
        )
        val done = s.trips.single { it.id == 1L }
        assertEquals(15.0, done.economyKmPerL!!, 1e-9)
        assertEquals(3, done.harshEvents)
        assertEquals(1, done.overspeedEvents)
        assertFalse(done.recording)
        val open = s.trips.single { it.id == 2L }
        assertTrue(open.recording)
        assertNull(open.economyKmPerL)
    }

    @Test fun `formats`() {
        assertEquals("1:02:05", TripFormat.elapsed(3_725.0))
        assertEquals("0:00:00", TripFormat.elapsed(-3.0))
        assertEquals(99.9, TripFormat.economyKmPerL(500.0, 1.0)!!, 1e-9)
        assertNull(TripFormat.economyKmPerL(10.0, 0.0))
    }
}
