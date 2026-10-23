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
}
