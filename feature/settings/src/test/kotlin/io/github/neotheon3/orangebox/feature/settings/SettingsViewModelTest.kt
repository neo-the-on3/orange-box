package io.github.neotheon3.orangebox.feature.settings

import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.model.BaseUrlValidator
import io.github.neotheon3.orangebox.core.model.AppVersion
import io.github.neotheon3.orangebox.core.model.SelectedDongle
import io.github.neotheon3.orangebox.core.model.Vehicle
import io.github.neotheon3.orangebox.core.testing.FakeAppPreferencesRepository
import io.github.neotheon3.orangebox.core.testing.FakeBackendConfigRepository
import io.github.neotheon3.orangebox.core.testing.FakeDongleSelectionRepository
import io.github.neotheon3.orangebox.core.testing.FakeVehicleRepository
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
        val vm = vm(repo)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.onModeSelected(BackendMode.FAKE)
        assertEquals(BackendMode.FAKE, repo.config.first().mode)
        assertEquals(BackendMode.FAKE, vm.uiState.value.config.mode)
    }

    @Test fun `cleartext url is rejected and not stored`() = runTest {
        val repo = FakeBackendConfigRepository()
        val vm = vm(repo)
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

    private fun vm(
        repo: FakeBackendConfigRepository,
        prefs: FakeAppPreferencesRepository = FakeAppPreferencesRepository(),
        vehicles: FakeVehicleRepository = FakeVehicleRepository(),
        dongles: FakeDongleSelectionRepository = FakeDongleSelectionRepository(),
    ) = SettingsViewModel(repo, AppVersion.UNKNOWN, prefs, vehicles, dongles)

    @Test fun `setup rows show the active vehicle, the dongle and the auto-start switch`() = runTest {
        val prefs = FakeAppPreferencesRepository()
        val vehicles = FakeVehicleRepository()
        val dongles = FakeDongleSelectionRepository()
        val vm = vm(FakeBackendConfigRepository(), prefs, vehicles, dongles)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(null, vm.uiState.value.vehicleName)
        assertEquals(null, vm.uiState.value.dongleName)
        val id = vehicles.save(Vehicle(name = "Swift"))
        vehicles.setActive(id)
        dongles.select(SelectedDongle("00:11:22:33:44:55", "OBDII"))
        vm.onAutoStartChanged(true)
        assertEquals("Swift", vm.uiState.value.vehicleName)
        assertEquals("OBDII", vm.uiState.value.dongleName)
        assertEquals(true, vm.uiState.value.autoStart)
        assertEquals(true, prefs.preferences.first().autoStartOnDongleConnect)
    }
}
