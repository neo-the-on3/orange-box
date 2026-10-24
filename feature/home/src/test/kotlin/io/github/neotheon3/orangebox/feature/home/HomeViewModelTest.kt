package io.github.neotheon3.orangebox.feature.home

import app.cash.turbine.test
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.testing.FakeBackendConfigRepository
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `reflects backend mode and remote warning`() = runTest {
        val repo = FakeBackendConfigRepository()
        val vm = HomeViewModel(repo, io.github.neotheon3.orangebox.core.testing.FakeObdConnection(), io.github.neotheon3.orangebox.core.testing.FakeTelemetrySource(), io.github.neotheon3.orangebox.core.telemetry.RecordedTripTotals.NONE)
        vm.uiState.test {
            var s = awaitItem()
            if (s.loading) s = awaitItem()
            assertEquals(BackendMode.OFF, s.backend.mode)
            assertFalse(s.showRemoteWarning)
            repo.setMode(BackendMode.REMOTE)
            assertTrue(awaitItem().showRemoteWarning)
            repo.setBaseUrl("https://api.example.com")
            assertFalse(awaitItem().showRemoteWarning)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
