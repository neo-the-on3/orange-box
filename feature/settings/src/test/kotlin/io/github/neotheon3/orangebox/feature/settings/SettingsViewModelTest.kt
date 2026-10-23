package io.github.neotheon3.orangebox.feature.settings

import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.model.BaseUrlValidator
import io.github.neotheon3.orangebox.core.testing.FakeBackendConfigRepository
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun `selecting a mode persists it`() = runTest {
        val repo = FakeBackendConfigRepository()
        val vm = SettingsViewModel(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onModeSelected(BackendMode.FAKE)
        assertEquals(BackendMode.FAKE, repo.config.first().mode)
        assertEquals(BackendMode.FAKE, vm.uiState.value.config.mode)
    }

    @Test fun `cleartext url is rejected and not stored`() = runTest {
        val repo = FakeBackendConfigRepository()
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
