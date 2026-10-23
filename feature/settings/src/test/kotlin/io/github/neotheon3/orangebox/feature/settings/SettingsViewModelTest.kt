package io.github.neotheon3.orangebox.feature.settings

import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.model.BaseUrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private class FakeRepo : BackendConfigRepository {
        val state = MutableStateFlow(BackendConfig())
        override val config = state
        override suspend fun setMode(mode: BackendMode) { state.value = state.value.copy(mode = mode) }
        override suspend fun setBaseUrl(url: String): BaseUrlValidator.Result {
            val r = BaseUrlValidator.validate(url)
            if (r == BaseUrlValidator.Result.Valid) state.value = state.value.copy(baseUrl = url.trim())
            return r
        }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `selecting a mode persists it`() = runTest {
        val repo = FakeRepo()
        val vm = SettingsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onModeSelected(BackendMode.FAKE)
        assertEquals(BackendMode.FAKE, repo.config.first().mode)
        assertEquals(BackendMode.FAKE, vm.uiState.value.config.mode)
    }

    @Test fun `cleartext url is rejected and not stored`() = runTest {
        val repo = FakeRepo()
        val vm = SettingsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onUrlChanged("http://14.63.216.111:9801")
        vm.onSaveUrl()
        assertEquals(BaseUrlValidator.Result.NotHttps, vm.uiState.value.urlResult)
        assertEquals("", repo.config.first().baseUrl)
        vm.onUrlChanged("https://api.example.com")
        vm.onSaveUrl()
        assertEquals(BaseUrlValidator.Result.Valid, vm.uiState.value.urlResult)
        assertEquals("https://api.example.com", repo.config.first().baseUrl)
    }
}
