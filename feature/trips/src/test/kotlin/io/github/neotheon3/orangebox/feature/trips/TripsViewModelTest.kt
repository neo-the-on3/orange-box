package io.github.neotheon3.orangebox.feature.trips

import io.github.neotheon3.orangebox.core.testing.FakeTripRepository
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
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
import java.time.YearMonth
import java.util.Locale

class TripsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val clock = Clock.fixed(millis(LocalDateTime.of(2026, 10, 24, 20, 0)).let { java.time.Instant.ofEpochMilli(it) }, TEST_ZONE)
    private val repo = FakeTripRepository(
        listOf(
            trip(1, LocalDateTime.of(2026, 10, 3, 8, 0)),
            trip(2, LocalDateTime.of(2026, 10, 9, 8, 0)),
            trip(3, LocalDateTime.of(2026, 9, 12, 8, 0)),
        ),
    )

    @Test fun `opens on the current month and navigates between months`() = runTest {
        val vm = TripsViewModel(repo, clock, Locale.UK)
        backgroundScope.launch(main.testDispatcher) { vm.uiState.collect {} }
        assertEquals(YearMonth.of(2026, 10), vm.uiState.value.month)
        assertEquals(DayOfWeek.MONDAY, vm.uiState.value.firstDayOfWeek)
        assertEquals(listOf(2L, 1L), vm.uiState.value.trips.map { it.id })
        vm.previousMonth()
        assertEquals(listOf(3L), vm.uiState.value.trips.map { it.id })
        vm.nextMonth(); vm.nextMonth()
        assertEquals(YearMonth.of(2026, 11), vm.uiState.value.month)
        assertEquals(true, vm.uiState.value.isEmptyMonth)
    }

    @Test fun `tapping a day filters, tapping it again clears, new trips appear live`() = runTest {
        val vm = TripsViewModel(repo, clock, Locale.US)
        backgroundScope.launch(main.testDispatcher) { vm.uiState.collect {} }
        assertEquals(DayOfWeek.SUNDAY, vm.uiState.value.firstDayOfWeek)
        vm.onDayClicked(LocalDate.of(2026, 10, 9))
        assertEquals(listOf(2L), vm.uiState.value.trips.map { it.id })
        repo.start(trip(0, LocalDateTime.of(2026, 10, 9, 19, 0)))
        assertEquals(2, vm.uiState.value.trips.size)
        vm.onDayClicked(LocalDate.of(2026, 10, 9))
        assertNull(vm.uiState.value.selectedDay)
        assertEquals("October only", 3, vm.uiState.first().summary.trips)
    }
}
