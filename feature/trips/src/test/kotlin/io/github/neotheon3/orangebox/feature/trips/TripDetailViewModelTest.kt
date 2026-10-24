package io.github.neotheon3.orangebox.feature.trips

import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import io.github.neotheon3.orangebox.core.testing.FakeAppPreferencesRepository
import io.github.neotheon3.orangebox.core.testing.FakeTripRepository
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDateTime

class TripDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val t = trip(7, LocalDateTime.of(2026, 10, 21, 9, 10), km = 10.0)
    private val repo = FakeTripRepository(
        listOf(t),
        samplesByTrip = mutableMapOf(7L to listOf(TripSampleEntity(tripId = 7, timestampMillis = t.startedAtMillis, speedKph = 30.0, rpm = null, fuelFlowLph = null, coolantC = null))),
    )

    private val prefs = FakeAppPreferencesRepository()

    @Test fun `loads the trip and follows the tiles preference`() = runTest {
        val vm = TripDetailViewModel(7, repo, prefs, TEST_ZONE)
        backgroundScope.launch(main.testDispatcher) { vm.uiState.collect {} }
        val s = vm.uiState.value
        assertFalse(s.loading)
        assertEquals(10.0, s.detail!!.metrics.distanceKm, 1e-9)
        assertEquals(1, s.detail!!.speedSeries.size)
        assertTrue(s.tilesEnabled)
        vm.setTilesEnabled(false)
        assertFalse(prefs.state.value.mapTilesEnabled)
        assertFalse(vm.uiState.value.tilesEnabled)
    }

    @Test fun `updates while the trip is still recording, and reports a deleted trip`() = runTest {
        val vm = TripDetailViewModel(7, repo, prefs, TEST_ZONE)
        backgroundScope.launch(main.testDispatcher) { vm.uiState.collect {} }
        repo.checkpoint(t.copy(distanceKm = 11.5), emptyList(), emptyList())
        assertEquals(11.5, vm.uiState.value.detail!!.metrics.distanceKm, 1e-9)
        repo.set(emptyList())
        assertTrue(vm.uiState.value.notFound)
    }
}
