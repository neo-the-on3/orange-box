package io.github.neotheon3.orangebox.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.database.VehicleRepository
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.datastore.DongleSelectionRepository
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.model.AppVersion
import io.github.neotheon3.orangebox.core.model.BaseUrlValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.neotheon3.orangebox.core.driving.DeviceCountry
import io.github.neotheon3.orangebox.core.driving.LaunchableApp
import io.github.neotheon3.orangebox.core.driving.QuickLaunchApps
import io.github.neotheon3.orangebox.core.model.DashboardButton
import io.github.neotheon3.orangebox.core.model.DashboardPreferences
import io.github.neotheon3.orangebox.core.model.EmergencyNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Whether full-screen notifications are allowed (an interface so tests can answer). */
fun interface FullScreenCheck {
    fun allowed(): Boolean
}

data class SettingsUiState(
    val config: BackendConfig = BackendConfig(),
    val urlDraft: String = "",
    val urlResult: BaseUrlValidator.Result? = null,
    val version: AppVersion = AppVersion.UNKNOWN,
    /** Active vehicle name, null when none is set up. */
    val vehicleName: String? = null,
    /** Selected dongle name, null when none is selected. */
    val dongleName: String? = null,
    val autoStart: Boolean = false,
    val dashboard: DashboardSettingsState = DashboardSettingsState(),
    /** Display units in effect (#58): the user's choice, else the country's defaults. */
    val units: io.github.neotheon3.orangebox.core.model.UnitPreferences = io.github.neotheon3.orangebox.core.model.UnitPreferences(),
    val alerts: io.github.neotheon3.orangebox.core.model.AlertPreferences = io.github.neotheon3.orangebox.core.model.AlertPreferences(),
    val crash: CrashSettingsState = CrashSettingsState(),
)

/** The "Driving dashboard" section. */
data class DashboardSettingsState(
    val prefs: DashboardPreferences = DashboardPreferences(),
    /** Country the default emergency number comes from (ISO), null if unknown. */
    val country: String? = null,
    val defaultEmergencyNumber: String = EmergencyNumbers.FALLBACK,
    /** The chosen quick-launch app if still installed. */
    val launchApp: LaunchableApp? = null,
    /** False on API 34+ when the user has not allowed full-screen notifications. */
    val fullScreenAllowed: Boolean = true,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: BackendConfigRepository,
    private val version: AppVersion,
    private val prefs: AppPreferencesRepository,
    vehicles: VehicleRepository,
    dongles: DongleSelectionRepository,
    private val country: DeviceCountry,
    private val apps: QuickLaunchApps,
    private val fullScreen: FullScreenCheck,
) : ViewModel() {
    private val fullScreenAllowed = MutableStateFlow(fullScreen.allowed())

    /** Launchable apps for the quick-launcher picker; null until [loadApps]. */
    private val _apps = MutableStateFlow<List<LaunchableApp>?>(null)
    val launchableApps: StateFlow<List<LaunchableApp>?> = _apps

    private val dashboard = combine(prefs.preferences.map { it.dashboard }.distinctUntilChanged(), fullScreenAllowed) { d, fs ->
        DashboardSettingsState(
            prefs = d,
            country = country.iso(),
            defaultEmergencyNumber = country.defaultEmergencyNumber(),
            launchApp = d.quickLaunchPackage?.let { withContext(Dispatchers.IO) { apps.find(it) } },
            fullScreenAllowed = fs,
        )
    }

    private val urlDraft = MutableStateFlow<String?>(null)
    private val urlResult = MutableStateFlow<BaseUrlValidator.Result?>(null)

    private val setup = combine(vehicles.activeVehicle, dongles.selected, prefs.preferences) { v, d, p ->
        Triple(v?.name, d?.name?.ifBlank { d.address }, p.autoStartOnDongleConnect)
    }

    /** The sections M7/M8 added, all from AppPreferences, as a partial state merged below. */
    private val contactDraft = MutableStateFlow<String?>(null)
    private val contactError = MutableStateFlow(false)

    private val general = combine(prefs.preferences, contactDraft, contactError) { p, draft, error ->
        SettingsUiState(units = country.units(p), alerts = p.alerts, crash = CrashSettingsState(p.crash, draft, error))
    }.distinctUntilChanged()

    private val backend = combine(repository.config, urlDraft, urlResult) { config, draft, result -> Triple(config, draft, result) }

    val uiState: StateFlow<SettingsUiState> =
        combine(backend, setup, dashboard, general) { (config, draft, result), (vehicle, dongle, autoStart), dash, g ->
            g.copy(
                config = config, urlDraft = draft ?: config.baseUrl, urlResult = result, version = version,
                vehicleName = vehicle, dongleName = dongle, autoStart = autoStart, dashboard = dash,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(version = version))

    fun onModeSelected(mode: BackendMode) {
        viewModelScope.launch { repository.setMode(mode) }
    }

    fun onUnitsChanged(units: io.github.neotheon3.orangebox.core.model.UnitPreferences) {
        viewModelScope.launch { prefs.setUnits(units) }
    }

    fun onAlertsChanged(alerts: io.github.neotheon3.orangebox.core.model.AlertPreferences) {
        viewModelScope.launch { prefs.setAlerts(alerts) }
    }

    /** Crash detection on/off, threshold and countdown; the contact is kept as stored. */
    fun onCrashChanged(crash: io.github.neotheon3.orangebox.core.model.CrashPreferences) {
        viewModelScope.launch {
            val stored = prefs.preferences.first().crash
            prefs.setCrash(crash.copy(contactNumber = stored.contactNumber, contactName = stored.contactName))
        }
    }

    fun onEditContact() {
        viewModelScope.launch { contactDraft.value = prefs.preferences.first().crash.contactNumber.orEmpty() }
        contactError.value = false
    }

    fun onContactDraftChanged(value: String) {
        contactDraft.value = value.filter { it.isDigit() || it in "+ -()./" }.take(24)
        contactError.value = false
    }

    fun onCancelContact() {
        contactDraft.value = null
        contactError.value = false
    }

    /** Saves the typed number if it is usable (#60); otherwise keeps the field open with an error. */
    fun onSaveContact() {
        val number = io.github.neotheon3.orangebox.core.model.CrashPreferences.normalizeContactNumber(contactDraft.value)
        if (number == null) {
            contactError.value = true
            return
        }
        setContact(number, name = null)
        contactDraft.value = null
    }

    /** A number from the system contact picker; null when nothing usable came back. */
    fun onContactPicked(contact: PickedContact?) {
        contactDraft.value = null
        val number = io.github.neotheon3.orangebox.core.model.CrashPreferences.normalizeContactNumber(contact?.number)
        if (number == null) {
            contactError.value = contact != null
            return
        }
        setContact(number, contact?.name)
    }

    fun onClearContact() = setContact(null, null)

    private fun setContact(number: String?, name: String?) {
        contactError.value = false
        viewModelScope.launch {
            val crash = prefs.preferences.first().crash
            prefs.setCrash(crash.copy(contactNumber = number, contactName = name))
        }
    }

    fun onAutoStartChanged(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoStartOnDongleConnect(enabled) }
    }

    private fun updateDashboard(transform: (DashboardPreferences) -> DashboardPreferences) {
        viewModelScope.launch { prefs.setDashboard(transform(prefs.preferences.first().dashboard)) }
    }

    fun onDashboardButtonChanged(button: DashboardButton) = updateDashboard { it.copy(button = button) }

    /** Saves [number] when valid; blank (or the country default) goes back to following the country. */
    fun onEmergencyNumberChanged(number: String) {
        val n = number.trim()
        when {
            n.isEmpty() || n == country.defaultEmergencyNumber() -> updateDashboard { it.copy(emergencyNumber = null) }
            DashboardPreferences.isValidEmergencyNumber(n) -> updateDashboard { it.copy(emergencyNumber = n) }
        }
    }

    fun onQuickLaunchAppChosen(packageName: String) =
        updateDashboard { it.copy(button = DashboardButton.QUICK_LAUNCHER, quickLaunchPackage = packageName) }

    fun onOverspeedChanged(kph: Int) = updateDashboard { it.copy(overspeedKph = DashboardPreferences.clampOverspeed(kph)) }

    fun onAutoShowDashboardChanged(enabled: Boolean) {
        updateDashboard { it.copy(autoShowDashboard = enabled) }
        refreshFullScreen()
    }

    /** On resume: the user may have changed the full-screen permission in system settings. */
    fun refreshFullScreen() { fullScreenAllowed.value = fullScreen.allowed() }

    fun loadApps() {
        if (_apps.value != null) return
        viewModelScope.launch { _apps.value = withContext(Dispatchers.IO) { apps.list() } }
    }

    fun onUrlChanged(value: String) {
        urlDraft.value = value
        urlResult.value = null
    }

    fun onSaveUrl() {
        viewModelScope.launch {
            val draft = urlDraft.value ?: repository.config.first().baseUrl
            urlResult.value = repository.setBaseUrl(draft)
        }
    }
}
