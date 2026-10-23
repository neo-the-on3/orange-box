package io.github.neotheon3.orangebox.feature.home

import app.cash.turbine.test
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.model.BaseUrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private class FakeRepo : BackendConfigRepository {
        val state = MutableStateFlow(BackendConfig())
        override val config = state
        override suspend fun setMode(mode: BackendMode) { state.value = state.value.copy(mode = mode) }
        override suspend fun setBaseUrl(url: String): BaseUrlValidator.Result {
            state.value = state.value.copy(baseUrl = url); return BaseUrlValidator.validate(url)
        }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `reflects backend mode and remote warning`() = runTest {
        val repo = FakeRepo()
        val vm = HomeViewModel(repo)
        vm.uiState.test {
            var s = awaitItem()
            if (s.loading) s = awaitItem()
            assertEquals(BackendMode.OFF, s.backend.mode)
            assertFalse(s.showRemoteWarning)
            repo.setMode(BackendMode.REMOTE)
            s = awaitItem()
            assertTrue(s.showRemoteWarning)
            repo.setBaseUrl("https://api.example.com")
            assertFalse(awaitItem().showRemoteWarning)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
