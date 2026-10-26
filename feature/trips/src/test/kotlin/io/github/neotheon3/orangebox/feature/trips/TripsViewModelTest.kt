package io.github.neotheon3.orangebox.feature.trips

import io.github.neotheon3.orangebox.core.model.Vehicle
import io.github.neotheon3.orangebox.core.testing.FakeBackendAccount
import io.github.neotheon3.orangebox.core.testing.FakeTripReassignment
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

    @Test fun `#203 - trips of a vehicle deleted elsewhere are flagged and move to the picked vehicle, then sync`() = runTest {
        val reassign = FakeTripReassignment(setOf(1L, 3L), listOf(Vehicle(id = 5, name = "Celerio"), Vehicle(id = 6, name = "Swift")))
        val account = FakeBackendAccount()
        val vm = TripsViewModel(repo, clock, Locale.UK, reassign, account)
        backgroundScope.launch(main.testDispatcher) { vm.uiState.collect {} }
        assertEquals("across months", setOf(1L, 3L), vm.uiState.value.orphanedTripIds)
        vm.startMoveOrphaned()
        val dialog = vm.uiState.value.moveDialog!!
        assertEquals(setOf(1L, 3L), dialog.tripIds)
        assertNull("two targets: the user picks", dialog.selected)
        vm.confirmMove()
        assertEquals("nothing picked, nothing moved", 0, reassign.moves.size)
        vm.selectMoveTarget(6)
        vm.confirmMove()
        assertEquals(listOf(setOf(1L, 3L) to 6L), reassign.moves)
        assertNull(vm.uiState.value.moveDialog)
        assertEquals(emptySet<Long>(), vm.uiState.value.orphanedTripIds)
        assertEquals(1, account.syncRequests)
    }

    @Test fun `#203 - a single target is preselected and cancelling moves nothing`() = runTest {
        val reassign = FakeTripReassignment(setOf(2L), listOf(Vehicle(id = 5, name = "Celerio")))
        val account = FakeBackendAccount()
        val vm = TripsViewModel(repo, clock, Locale.UK, reassign, account)
        backgroundScope.launch(main.testDispatcher) { vm.uiState.collect {} }
        vm.startMoveOrphaned()
        assertEquals(5L, vm.uiState.value.moveDialog!!.selected)
        vm.dismissMove()
        assertNull(vm.uiState.value.moveDialog)
        assertEquals(0, reassign.moves.size)
        assertEquals(0, account.syncRequests)
    }
}
