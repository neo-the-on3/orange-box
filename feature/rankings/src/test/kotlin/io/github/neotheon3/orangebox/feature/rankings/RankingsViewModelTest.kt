package io.github.neotheon3.orangebox.feature.rankings

import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.FuelTypes
import io.github.neotheon3.orangebox.core.network.Periods
import io.github.neotheon3.orangebox.core.network.RankingMetrics
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.network.VehicleCreateDto
import io.github.neotheon3.orangebox.core.testing.FakeMemberBackend
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** The member screens over Fake mode's in-process server (#66 / #68): no network. */
@OptIn(ExperimentalCoroutinesApi::class)
class RankingsViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val now = Instant.parse("2026-10-21T08:00:00Z")
    private val server = FakeBackendApi(clock = { now }).apply { rankingPageSize = 5; ledgerPageSize = 2 }
    private val backend = FakeMemberBackend(server)

    private suspend fun seed() {
        val v = server.createVehicle(VehicleCreateDto("cv", fuelType = FuelTypes.GASOLINE, nickname = "Car"), "k")
        listOf(1L, 2L).forEach { i ->
            val start = now.minusSeconds(i * 7200)
            server.seedTrip(
                TripUploadDto(
                    "t$i", v.id, TripUploadDto.STATE_COMPLETED, start, start.plusSeconds(1800), endedAt = start.plusSeconds(1800),
                    durationS = 1800, distanceKm = 15.0, ecoSpeedTimeS = 1000.0, harshAccelCount = 1,
                ),
            )
        }
    }

    private fun TestScope.vm(available: MutableStateFlow<Boolean> = backend.available) =
        RankingsViewModel(MemberRepository(backend) { ZoneOffset.UTC }, available, flowOf(true)).also { runCurrent() }

    @Test fun `summary loads first and follows the period`() = runTest(main.testDispatcher) {
        seed()
        val vm = vm()
        val s = vm.uiState.value
        assertTrue(s.fake)
        assertEquals(2, (s.summary as Load.Ready).value.tripCount)
        vm.setSummaryPeriod(Periods.DAY)
        runCurrent()
        assertEquals(Periods.DAY, (vm.uiState.value.summary as Load.Ready).value.period)
    }

    @Test fun `rankings - opt in, appear, load more pages`() = runTest(main.testDispatcher) {
        seed()
        val vm = vm()
        vm.selectTab(MemberTab.RANKINGS)
        runCurrent()
        var s = vm.uiState.value
        assertEquals(false, s.optedIn)
        assertNull("not ranked before opting in", (s.me as Load.Ready).value)
        assertEquals(5, (s.board as Load.Ready).value.entries.size)

        vm.setRankingOptIn(true)
        runCurrent()
        s = vm.uiState.value
        assertTrue(s.optedIn)
        assertNotNull((s.me as Load.Ready).value)
        while ((vm.uiState.value.board as Load.Ready).value.nextCursor != null) {
            vm.loadMoreRanking()
            runCurrent()
        }
        val all = (vm.uiState.value.board as Load.Ready).value.entries
        assertEquals(13, all.size)
        assertEquals(1, all.count { it.isMe })

        vm.setMetric(RankingMetrics.HARSH_DRIVING)
        runCurrent()
        assertEquals(RankingMetrics.HARSH_DRIVING, vm.uiState.value.metric)
        assertEquals(5, (vm.uiState.value.board as Load.Ready).value.entries.size)
    }

    @Test fun `points - balance and paged ledger`() = runTest(main.testDispatcher) {
        seed()
        val vm = vm()
        vm.selectTab(MemberTab.POINTS)
        runCurrent()
        assertEquals(500 - 200 + 2 * 25, (vm.uiState.value.points as Load.Ready).value.remaining)
        assertEquals(2, (vm.uiState.value.ledger as Load.Ready).value.entries.size)
        vm.loadMoreLedger()
        runCurrent()
        vm.loadMoreLedger()
        runCurrent()
        val ledger = (vm.uiState.value.ledger as Load.Ready).value
        assertEquals(4, ledger.entries.size)
        assertNull(ledger.nextCursor)
    }

    @Test fun `server without the features - unavailable, and retry after it appears`() = runTest(main.testDispatcher) {
        server.memberFeaturesImplemented = false
        val vm = vm()
        vm.selectTab(MemberTab.RANKINGS)
        runCurrent()
        assertEquals(Load.Unavailable, vm.uiState.value.board)
        assertEquals(Load.Unavailable, vm.uiState.value.summary)
        server.memberFeaturesImplemented = true
        vm.retry()
        runCurrent()
        assertTrue(vm.uiState.value.board is Load.Ready)
    }

    @Test fun `signing in later loads the open tab`() = runTest(main.testDispatcher) {
        val available = MutableStateFlow(false)
        backend.available.value = false
        val vm = vm(available)
        assertEquals(false, vm.uiState.value.available)
        assertEquals(Load.SignedOut, vm.uiState.value.summary)
        backend.available.value = true
        available.value = true
        runCurrent()
        assertTrue(vm.uiState.value.summary is Load.Ready)
    }
}
