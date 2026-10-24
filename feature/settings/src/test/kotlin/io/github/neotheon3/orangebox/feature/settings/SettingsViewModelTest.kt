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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.driving.DeviceCountry
import io.github.neotheon3.orangebox.core.driving.QuickLaunchApps
import io.github.neotheon3.orangebox.core.model.DashboardButton
import io.github.neotheon3.orangebox.core.model.DashboardPreferences

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var fullScreenAllowed = true
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
    ) = SettingsViewModel(
        repo, AppVersion.UNKNOWN, prefs, vehicles, dongles,
        DeviceCountry(context), QuickLaunchApps(context), FullScreenCheck { fullScreenAllowed },
    )

    private fun setSimCountry(iso: String) {
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setSimCountryIso(iso)
    }

    @Test fun `emergency number defaults to the country and custom numbers are validated`() = runTest {
        setSimCountry("us")
        val prefs = FakeAppPreferencesRepository()
        val vm = vm(FakeBackendConfigRepository(), prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals("US", vm.uiState.value.dashboard.country)
        assertEquals("911", vm.uiState.value.dashboard.defaultEmergencyNumber)
        vm.onEmergencyNumberChanged("1")
        assertNull(prefs.preferences.first().dashboard.emergencyNumber)
        vm.onEmergencyNumberChanged("112")
        assertEquals("112", prefs.preferences.first().dashboard.emergencyNumber)
        vm.onEmergencyNumberChanged("911")
        assertNull("the country default is stored as 'follow the country'", prefs.preferences.first().dashboard.emergencyNumber)
        vm.onEmergencyNumberChanged("100")
        vm.onEmergencyNumberChanged("")
        assertNull(prefs.preferences.first().dashboard.emergencyNumber)
    }

    @Test fun `button mode, quick launch app, overspeed and auto-show persist`() = runTest {
        val pm = shadowOf(context.packageManager)
        val info = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.example.maps"; name = "com.example.maps.Main"; nonLocalizedLabel = "Maps"
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.example.maps" }
            }
            nonLocalizedLabel = "Maps"
        }
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), info)
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.example.maps"), info)
        val prefs = FakeAppPreferencesRepository()
        val vm = vm(FakeBackendConfigRepository(), prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.launchableApps.collect {} }

        assertEquals(DashboardButton.HAZARD, vm.uiState.value.dashboard.prefs.button)
        vm.loadApps()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        waitFor { vm.launchableApps.value != null }
        assertEquals(listOf("com.example.maps"), vm.launchableApps.value!!.map { it.packageName })
        vm.onQuickLaunchAppChosen("com.example.maps")
        waitFor { vm.uiState.value.dashboard.launchApp != null }
        assertEquals(DashboardButton.QUICK_LAUNCHER, vm.uiState.value.dashboard.prefs.button)
        assertEquals("Maps", vm.uiState.value.dashboard.launchApp?.label)

        vm.onOverspeedChanged(500)
        assertEquals(DashboardPreferences.MAX_OVERSPEED_KPH, prefs.preferences.first().dashboard.overspeedKph)
        vm.onOverspeedChanged(90)
        waitFor { vm.uiState.value.dashboard.prefs.overspeedKph == 90 }

        assertFalse(vm.uiState.value.dashboard.prefs.autoShowDashboard)
        vm.onAutoShowDashboardChanged(true)
        assertTrue(prefs.preferences.first().dashboard.autoShowDashboard)
        vm.onDashboardButtonChanged(DashboardButton.HAZARD)
        assertEquals(DashboardButton.HAZARD, prefs.preferences.first().dashboard.button)
        assertEquals("the chosen app is kept for later", "com.example.maps", prefs.preferences.first().dashboard.quickLaunchPackage)
    }

    @Test fun `full-screen permission is re-read on resume`() = runTest {
        fullScreenAllowed = false
        val vm = vm(FakeBackendConfigRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertFalse(vm.uiState.value.dashboard.fullScreenAllowed)
        fullScreenAllowed = true
        vm.refreshFullScreen()
        assertTrue(vm.uiState.value.dashboard.fullScreenAllowed)
    }

    /** The VM hops to Dispatchers.IO for package manager calls. */
    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out", condition())
    }

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
