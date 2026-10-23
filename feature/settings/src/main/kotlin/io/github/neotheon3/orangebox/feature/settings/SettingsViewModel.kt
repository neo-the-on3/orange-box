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
import javax.inject.Inject

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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: BackendConfigRepository,
    private val version: AppVersion,
    private val prefs: AppPreferencesRepository,
    vehicles: VehicleRepository,
    dongles: DongleSelectionRepository,
) : ViewModel() {

    private val urlDraft = MutableStateFlow<String?>(null)
    private val urlResult = MutableStateFlow<BaseUrlValidator.Result?>(null)

    private val setup = combine(vehicles.activeVehicle, dongles.selected, prefs.preferences) { v, d, p ->
        Triple(v?.name, d?.name?.ifBlank { d.address }, p.autoStartOnDongleConnect)
    }

    val uiState: StateFlow<SettingsUiState> =
        combine(repository.config, urlDraft, urlResult, setup) { config, draft, result, (vehicle, dongle, autoStart) ->
            SettingsUiState(
                config = config, urlDraft = draft ?: config.baseUrl, urlResult = result, version = version,
                vehicleName = vehicle, dongleName = dongle, autoStart = autoStart,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(version = version))

    fun onModeSelected(mode: BackendMode) {
        viewModelScope.launch { repository.setMode(mode) }
    }

    fun onAutoStartChanged(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoStartOnDongleConnect(enabled) }
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
