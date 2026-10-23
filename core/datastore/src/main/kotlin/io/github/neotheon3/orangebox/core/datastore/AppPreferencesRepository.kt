package io.github.neotheon3.orangebox.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.neotheon3.orangebox.core.model.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface AppPreferencesRepository {
    val preferences: Flow<AppPreferences>

    suspend fun setDisclaimerAccepted(accepted: Boolean)
    suspend fun setOnboardingComplete(complete: Boolean)
    suspend fun setAutoStartOnDongleConnect(enabled: Boolean)
    suspend fun setActiveVehicle(id: Long?)
    suspend fun markPermissionsAsked(permissions: Collection<String>)
    /** Remembers the onboarding step in progress; null clears it. */
    suspend fun setOnboardingStep(step: String?)
}

@Singleton
class DataStoreAppPreferencesRepository @Inject constructor(
    @SettingsDataStore private val dataStore: DataStore<Preferences>,
) : AppPreferencesRepository {
    override val preferences: Flow<AppPreferences> = dataStore.data.map { p ->
        AppPreferences(
            onboardingComplete = p[ONBOARDING] ?: false,
            disclaimerAccepted = p[DISCLAIMER] ?: false,
            autoStartOnDongleConnect = p[AUTO_START] ?: false,
            activeVehicleId = p[VEHICLE],
            askedPermissions = p[ASKED].orEmpty(),
            onboardingStep = p[ONBOARDING_STEP],
        )
    }.distinctUntilChanged()

    override suspend fun setDisclaimerAccepted(accepted: Boolean) { dataStore.edit { it[DISCLAIMER] = accepted } }
    override suspend fun setOnboardingComplete(complete: Boolean) { dataStore.edit { it[ONBOARDING] = complete } }
    override suspend fun setAutoStartOnDongleConnect(enabled: Boolean) { dataStore.edit { it[AUTO_START] = enabled } }
    override suspend fun setActiveVehicle(id: Long?) {
        dataStore.edit { if (id == null) it.remove(VEHICLE) else it[VEHICLE] = id }
    }
    override suspend fun markPermissionsAsked(permissions: Collection<String>) {
        if (permissions.isEmpty()) return
        dataStore.edit { it[ASKED] = it[ASKED].orEmpty() + permissions }
    }

    override suspend fun setOnboardingStep(step: String?) {
        dataStore.edit { if (step == null) it.remove(ONBOARDING_STEP) else it[ONBOARDING_STEP] = step }
    }

    internal companion object {
        val ONBOARDING_STEP = stringPreferencesKey("onboarding_step")
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val DISCLAIMER = booleanPreferencesKey("disclaimer_accepted")
        val AUTO_START = booleanPreferencesKey("auto_start_on_dongle_connect")
        val VEHICLE = longPreferencesKey("active_vehicle_id")
        val ASKED = stringSetPreferencesKey("asked_permissions")
    }
}
