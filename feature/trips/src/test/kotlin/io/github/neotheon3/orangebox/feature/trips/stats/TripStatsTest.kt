package io.github.neotheon3.orangebox.feature.trips.stats

import io.github.neotheon3.orangebox.core.database.TripPeriodTotals
import io.github.neotheon3.orangebox.core.testing.FakeTripRepository
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import io.github.neotheon3.orangebox.feature.trips.TEST_ZONE
import io.github.neotheon3.orangebox.feature.trips.trip
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

class TripStatsTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `weeks start on the locale's first day, months are calendar months, newest first`() {
        val today = LocalDate.of(2026, 10, 25) // a Sunday
        val mon = TripStats.ranges(StatsPeriod.WEEK, today, 3, DayOfWeek.MONDAY)
        assertEquals(PeriodRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26)), mon[0])
        assertEquals(LocalDate.of(2026, 10, 5), mon[2].start)
        assertEquals(PeriodRange(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 1)), TripStats.ranges(StatsPeriod.WEEK, today, 1, DayOfWeek.SUNDAY)[0])
        val months = TripStats.ranges(StatsPeriod.MONTH, today, 3, DayOfWeek.MONDAY)
        assertEquals(PeriodRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)), months[0])
        assertEquals(PeriodRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)), months[2])
    }

    @Test fun `derived figures`() {
        val p = PeriodStats(PeriodRange(LocalDate.MIN, LocalDate.MAX), TripPeriodTotals(trips = 2, distanceKm = 50.0, durationS = 3600.0, fuelLitres = 4.0, ecoS = 900.0, harshAccel = 1, harshBrake = 2))
        assertEquals(12.5, p.economyKmPerL!!, 1e-9)
        assertEquals(0.25, p.ecoShare!!, 1e-9)
        assertEquals(6.0, p.harshPer100Km!!, 1e-9)
        val empty = PeriodStats(PeriodRange(LocalDate.MIN, LocalDate.MAX), TripPeriodTotals())
        assertNull(empty.economyKmPerL); assertNull(empty.ecoShare); assertNull(empty.harshPer100Km)
    }

    @Test fun `view model aggregates per week and month and follows new trips`() = runTest {
        val now = LocalDateTime.of(2026, 10, 25, 12, 0)
        val clock = Clock.fixed(now.atZone(TEST_ZONE).toInstant(), TEST_ZONE)
        val repo = FakeTripRepository(
            listOf(
                trip(1, LocalDateTime.of(2026, 10, 19, 0, 5), km = 10.0, litres = 1.0, harshBrake = 1), // this week (Mon)
                trip(2, LocalDateTime.of(2026, 10, 25, 8, 0), km = 30.0, litres = 2.0), // this week (Sun)
                trip(3, LocalDateTime.of(2026, 10, 18, 23, 59), km = 5.0), // last week, this month
                trip(4, LocalDateTime.of(2026, 9, 30, 9, 0), km = 7.0), // last month
                trip(5, LocalDateTime.of(2026, 10, 25, 11, 0), km = 99.0, open = true), // recording: left out
            ),
        )
        val vm = StatsViewModel(repo, clock, Locale.UK)
        backgroundScope.launch(main.testDispatcher) { vm.uiState.collect {} }
        var s = vm.uiState.value
        assertEquals(StatsPeriod.WEEK, s.period)
        assertEquals(StatsViewModel.COUNT, s.periods.size)
        assertEquals(LocalDate.of(2026, 10, 19), s.current!!.range.start)
        assertEquals(2, s.current!!.totals.trips)
        assertEquals(40.0, s.current!!.totals.distanceKm, 1e-9)
        assertEquals(1, s.current!!.totals.harshEvents)
        assertEquals(5.0, s.periods[1].totals.distanceKm, 1e-9)

        vm.setPeriod(StatsPeriod.MONTH)
        s = vm.uiState.value
        assertEquals(3, s.current!!.totals.trips)
        assertEquals(45.0, s.current!!.totals.distanceKm, 1e-9)
        assertEquals(7.0, s.periods[1].totals.distanceKm, 1e-9)

        repo.start(trip(0, LocalDateTime.of(2026, 10, 2, 10, 0), km = 5.0))
        assertEquals(50.0, vm.uiState.value.current!!.totals.distanceKm, 1e-9)
    }
}
