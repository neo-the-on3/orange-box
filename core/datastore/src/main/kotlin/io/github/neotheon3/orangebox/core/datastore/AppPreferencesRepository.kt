package io.github.neotheon3.orangebox.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.github.neotheon3.orangebox.core.model.AppPreferences
import io.github.neotheon3.orangebox.core.model.CrashPreferences
import io.github.neotheon3.orangebox.core.model.DashboardButton
import io.github.neotheon3.orangebox.core.model.DashboardPreferences
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
    /** Stores the dashboard settings (overspeed clamped, invalid emergency numbers dropped to the default). */
    suspend fun setDashboard(dashboard: DashboardPreferences)
    suspend fun setMapTilesEnabled(enabled: Boolean)
    /** Stores the crash-detection settings (threshold and countdown clamped). */
    suspend fun setCrash(crash: CrashPreferences)
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
            dashboard = DashboardPreferences(
                button = DashboardButton.entries.firstOrNull { it.name == p[DASH_BUTTON] } ?: DashboardButton.HAZARD,
                emergencyNumber = p[DASH_EMERGENCY],
                quickLaunchPackage = p[DASH_LAUNCH],
                overspeedKph = DashboardPreferences.clampOverspeed(p[DASH_OVERSPEED] ?: DashboardPreferences.DEFAULT_OVERSPEED_KPH),
                autoShowDashboard = p[DASH_AUTO_SHOW] ?: false,
            ),
            mapTilesEnabled = p[MAP_TILES] ?: true,
            crash = CrashPreferences(
                detectionEnabled = p[CRASH_ENABLED] ?: true,
                thresholdG = CrashPreferences.clampThreshold(p[CRASH_THRESHOLD] ?: CrashPreferences.DEFAULT_THRESHOLD_G),
                countdownSeconds = CrashPreferences.clampCountdown(p[CRASH_COUNTDOWN] ?: CrashPreferences.DEFAULT_COUNTDOWN_S),
            ),
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

    override suspend fun setMapTilesEnabled(enabled: Boolean) { dataStore.edit { it[MAP_TILES] = enabled } }

    override suspend fun setCrash(crash: CrashPreferences) {
        dataStore.edit {
            it[CRASH_ENABLED] = crash.detectionEnabled
            it[CRASH_THRESHOLD] = CrashPreferences.clampThreshold(crash.thresholdG)
            it[CRASH_COUNTDOWN] = CrashPreferences.clampCountdown(crash.countdownSeconds)
        }
    }

    override suspend fun setDashboard(dashboard: DashboardPreferences) {
        dataStore.edit {
            it[DASH_BUTTON] = dashboard.button.name
            val number = dashboard.emergencyNumber?.trim()?.takeIf(DashboardPreferences::isValidEmergencyNumber)
            if (number == null) it.remove(DASH_EMERGENCY) else it[DASH_EMERGENCY] = number
            if (dashboard.quickLaunchPackage == null) it.remove(DASH_LAUNCH) else it[DASH_LAUNCH] = dashboard.quickLaunchPackage!!
            it[DASH_OVERSPEED] = DashboardPreferences.clampOverspeed(dashboard.overspeedKph)
            it[DASH_AUTO_SHOW] = dashboard.autoShowDashboard
        }
    }

    internal companion object {
        val DASH_BUTTON = stringPreferencesKey("dashboard_button")
        val DASH_EMERGENCY = stringPreferencesKey("dashboard_emergency_number")
        val DASH_LAUNCH = stringPreferencesKey("dashboard_quick_launch_package")
        val DASH_OVERSPEED = intPreferencesKey("dashboard_overspeed_kph")
        val DASH_AUTO_SHOW = booleanPreferencesKey("dashboard_auto_show")
        val MAP_TILES = booleanPreferencesKey("map_tiles_enabled")
        val CRASH_ENABLED = booleanPreferencesKey("crash_detection_enabled")
        val CRASH_THRESHOLD = doublePreferencesKey("crash_threshold_g")
        val CRASH_COUNTDOWN = intPreferencesKey("crash_countdown_s")
        val ONBOARDING_STEP = stringPreferencesKey("onboarding_step")
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val DISCLAIMER = booleanPreferencesKey("disclaimer_accepted")
        val AUTO_START = booleanPreferencesKey("auto_start_on_dongle_connect")
        val VEHICLE = longPreferencesKey("active_vehicle_id")
        val ASKED = stringSetPreferencesKey("asked_permissions")
    }
}
