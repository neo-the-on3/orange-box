package io.github.neotheon3.orangebox.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.neotheon3.orangebox.core.model.AppPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `defaults then every field persists`() = runTest(UnconfinedTestDispatcher()) {
        val repo = DataStoreAppPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("p.preferences_pb").also { it.delete() } },
        )
        assertEquals(AppPreferences(), repo.preferences.first())
        repo.setDisclaimerAccepted(true)
        repo.setOnboardingComplete(true)
        repo.setAutoStartOnDongleConnect(true)
        repo.setActiveVehicle(7)
        repo.markPermissionsAsked(listOf("a"))
        repo.markPermissionsAsked(listOf("b"))
        assertEquals(AppPreferences(true, true, true, 7, setOf("a", "b")), repo.preferences.first())
        repo.setActiveVehicle(null)
        assertEquals(null, repo.preferences.first().activeVehicleId)
        repo.setOnboardingStep("VEHICLE")
        assertEquals("VEHICLE", repo.preferences.first().onboardingStep)
        repo.setOnboardingStep(null)
        assertEquals(null, repo.preferences.first().onboardingStep)
    }

    @Test fun `dashboard settings persist, clamp and validate`() = runTest(UnconfinedTestDispatcher()) {
        val repo = DataStoreAppPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("d.preferences_pb").also { it.delete() } },
        )
        assertEquals(io.github.neotheon3.orangebox.core.model.DashboardPreferences(), repo.preferences.first().dashboard)
        val chosen = io.github.neotheon3.orangebox.core.model.DashboardPreferences(
            button = io.github.neotheon3.orangebox.core.model.DashboardButton.QUICK_LAUNCHER,
            emergencyNumber = " 911 ", quickLaunchPackage = "org.example.maps", overspeedKph = 100, autoShowDashboard = true,
        )
        repo.setDashboard(chosen)
        assertEquals(chosen.copy(emergencyNumber = "911"), repo.preferences.first().dashboard)
        repo.setDashboard(chosen.copy(emergencyNumber = "call me", overspeedKph = 500, quickLaunchPackage = null))
        val d = repo.preferences.first().dashboard
        assertEquals(null, d.emergencyNumber)
        assertEquals(200, d.overspeedKph)
        assertEquals(null, d.quickLaunchPackage)
    }

    @Test fun `crash settings default off, persist and clamp`() = runTest(UnconfinedTestDispatcher()) {
        val repo = DataStoreAppPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("c.preferences_pb").also { it.delete() } },
        )
        val defaults = repo.preferences.first().crash
        assertEquals(io.github.neotheon3.orangebox.core.model.CrashPreferences(), defaults)
        assertEquals("crash detection off by default", false, defaults.detectionEnabled)
        repo.setCrash(defaults.copy(detectionEnabled = true, thresholdG = 5.5, countdownSeconds = 20))
        assertEquals(io.github.neotheon3.orangebox.core.model.CrashPreferences(true, 5.5, 20), repo.preferences.first().crash)
        repo.setCrash(defaults.copy(thresholdG = 99.0, countdownSeconds = 1))
        val c = repo.preferences.first().crash
        assertEquals(io.github.neotheon3.orangebox.core.model.CrashPreferences.MAX_THRESHOLD_G, c.thresholdG, 0.0)
        assertEquals(io.github.neotheon3.orangebox.core.model.CrashPreferences.MIN_COUNTDOWN_S, c.countdownSeconds)
    }

    @Test fun `units follow the country until chosen, then persist`() = runTest(UnconfinedTestDispatcher()) {
        val repo = DataStoreAppPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("u.preferences_pb").also { it.delete() } },
        )
        assertEquals(null, repo.preferences.first().units)
        val chosen = io.github.neotheon3.orangebox.core.model.UnitPreferences(
            io.github.neotheon3.orangebox.core.model.DistanceUnit.MI,
            io.github.neotheon3.orangebox.core.model.EconomyUnit.L_PER_100KM,
            io.github.neotheon3.orangebox.core.model.TemperatureUnit.FAHRENHEIT,
        )
        repo.setUnits(chosen)
        assertEquals(chosen, repo.preferences.first().units)
    }

    @Test fun `drive-start diagnostics prompt is on by default and can be turned off`() = runTest(UnconfinedTestDispatcher()) {
        val repo = DataStoreAppPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("d.preferences_pb").also { it.delete() } },
        )
        assertEquals(true, repo.preferences.first().diagnosticsPromptEnabled)
        repo.setDiagnosticsPromptEnabled(false)
        assertEquals(false, repo.preferences.first().diagnosticsPromptEnabled)
    }

    @Test fun `alert toggles default and persist (#59)`() = runTest(UnconfinedTestDispatcher()) {
        val repo = DataStoreAppPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("a.preferences_pb").also { it.delete() } },
        )
        val defaults = repo.preferences.first().alerts
        assertEquals(io.github.neotheon3.orangebox.core.model.AlertPreferences(), defaults)
        assertEquals("idling off by default", false, defaults.idling)
        assertEquals("voice off by default", false, defaults.voice)
        val changed = io.github.neotheon3.orangebox.core.model.AlertPreferences(
            overspeed = false, idling = true, harsh = false, mil = false, sound = false, voice = true,
        )
        repo.setAlerts(changed)
        assertEquals(changed, repo.preferences.first().alerts)
    }

    @Test fun `emergency contact is stored normalized and an invalid one is dropped (#60)`() = runTest(UnconfinedTestDispatcher()) {
        val repo = DataStoreAppPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("c.preferences_pb").also { it.delete() } },
        )
        val base = io.github.neotheon3.orangebox.core.model.CrashPreferences()
        assertEquals(null, repo.preferences.first().crash.contactNumber)
        repo.setCrash(base.copy(contactNumber = "+91 98765 43210", contactName = " Asha "))
        assertEquals("+919876543210", repo.preferences.first().crash.contactNumber)
        assertEquals("Asha", repo.preferences.first().crash.contactName)
        repo.setCrash(base.copy(contactNumber = "not a number", contactName = "Asha"))
        assertEquals(null, repo.preferences.first().crash.contactNumber)
        assertEquals("no name without a number", null, repo.preferences.first().crash.contactName)
    }
}
