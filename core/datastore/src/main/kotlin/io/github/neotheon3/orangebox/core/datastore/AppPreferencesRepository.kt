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
import io.github.neotheon3.orangebox.core.model.DistanceUnit
import io.github.neotheon3.orangebox.core.model.EconomyUnit
import io.github.neotheon3.orangebox.core.model.TemperatureUnit
import io.github.neotheon3.orangebox.core.model.UnitPreferences
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
    /** Stores the crash-detection settings (threshold and countdown clamped, an invalid contact number dropped). */
    suspend fun setCrash(crash: CrashPreferences)
    /** Stores the display units; from then on they no longer follow the device country. */
    suspend fun setUnits(units: UnitPreferences)
    suspend fun setAlerts(alerts: io.github.neotheon3.orangebox.core.model.AlertPreferences)
    suspend fun setDiagnosticsPromptEnabled(enabled: Boolean)
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
                detectionEnabled = p[CRASH_ENABLED] ?: CrashPreferences().detectionEnabled,
                thresholdG = CrashPreferences.clampThreshold(p[CRASH_THRESHOLD] ?: CrashPreferences.DEFAULT_THRESHOLD_G),
                countdownSeconds = CrashPreferences.clampCountdown(p[CRASH_COUNTDOWN] ?: CrashPreferences.DEFAULT_COUNTDOWN_S),
                contactNumber = p[CRASH_CONTACT_NUMBER],
                contactName = p[CRASH_CONTACT_NAME],
            ),
            units = p[UNITS_DISTANCE]?.let { d ->
                UnitPreferences(
                    distance = DistanceUnit.entries.firstOrNull { it.name == d } ?: DistanceUnit.KM,
                    economy = EconomyUnit.entries.firstOrNull { it.name == p[UNITS_ECONOMY] } ?: EconomyUnit.KM_PER_L,
                    temperature = TemperatureUnit.entries.firstOrNull { it.name == p[UNITS_TEMPERATURE] } ?: TemperatureUnit.CELSIUS,
                )
            },
            alerts = io.github.neotheon3.orangebox.core.model.AlertPreferences().let { d ->
                io.github.neotheon3.orangebox.core.model.AlertPreferences(
                    overspeed = p[ALERT_OVERSPEED] ?: d.overspeed,
                    idling = p[ALERT_IDLING] ?: d.idling,
                    harsh = p[ALERT_HARSH] ?: d.harsh,
                    mil = p[ALERT_MIL] ?: d.mil,
                    sound = p[ALERT_SOUND] ?: d.sound,
                    voice = p[ALERT_VOICE] ?: d.voice,
                )
            },
            diagnosticsPromptEnabled = p[DIAGNOSTICS_PROMPT] ?: true,
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
            val number = CrashPreferences.normalizeContactNumber(crash.contactNumber)
            val name = crash.contactName?.trim()?.takeIf { n -> n.isNotEmpty() && number != null }
            if (number == null) it.remove(CRASH_CONTACT_NUMBER) else it[CRASH_CONTACT_NUMBER] = number
            if (name == null) it.remove(CRASH_CONTACT_NAME) else it[CRASH_CONTACT_NAME] = name
        }
    }

    override suspend fun setUnits(units: UnitPreferences) {
        dataStore.edit {
            it[UNITS_DISTANCE] = units.distance.name
            it[UNITS_ECONOMY] = units.economy.name
            it[UNITS_TEMPERATURE] = units.temperature.name
        }
    }

    override suspend fun setAlerts(alerts: io.github.neotheon3.orangebox.core.model.AlertPreferences) {
        dataStore.edit {
            it[ALERT_OVERSPEED] = alerts.overspeed
            it[ALERT_IDLING] = alerts.idling
            it[ALERT_HARSH] = alerts.harsh
            it[ALERT_MIL] = alerts.mil
            it[ALERT_SOUND] = alerts.sound
            it[ALERT_VOICE] = alerts.voice
        }
    }

    override suspend fun setDiagnosticsPromptEnabled(enabled: Boolean) { dataStore.edit { it[DIAGNOSTICS_PROMPT] = enabled } }

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
        val CRASH_CONTACT_NUMBER = stringPreferencesKey("crash_contact_number")
        val CRASH_CONTACT_NAME = stringPreferencesKey("crash_contact_name")
        val UNITS_DISTANCE = stringPreferencesKey("units_distance")
        val UNITS_ECONOMY = stringPreferencesKey("units_economy")
        val UNITS_TEMPERATURE = stringPreferencesKey("units_temperature")
        val ALERT_OVERSPEED = booleanPreferencesKey("alert_overspeed")
        val ALERT_IDLING = booleanPreferencesKey("alert_idling")
        val ALERT_HARSH = booleanPreferencesKey("alert_harsh")
        val ALERT_MIL = booleanPreferencesKey("alert_mil")
        val ALERT_SOUND = booleanPreferencesKey("alert_sound")
        val ALERT_VOICE = booleanPreferencesKey("alert_voice")
        val DIAGNOSTICS_PROMPT = booleanPreferencesKey("diagnostics_prompt_enabled")
        val ONBOARDING_STEP = stringPreferencesKey("onboarding_step")
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val DISCLAIMER = booleanPreferencesKey("disclaimer_accepted")
        val AUTO_START = booleanPreferencesKey("auto_start_on_dongle_connect")
        val VEHICLE = longPreferencesKey("active_vehicle_id")
        val ASKED = stringSetPreferencesKey("asked_permissions")
    }
}
