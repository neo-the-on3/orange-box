package io.github.neotheon3.orangebox.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: BackendConfigRepository,
) : ViewModel() {

    private val urlDraft = MutableStateFlow<String?>(null)
    private val urlResult = MutableStateFlow<BaseUrlValidator.Result?>(null)

    val uiState: StateFlow<SettingsUiState> =
        combine(repository.config, urlDraft, urlResult) { config, draft, result ->
            SettingsUiState(config = config, urlDraft = draft ?: config.baseUrl, urlResult = result)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onModeSelected(mode: BackendMode) {
        viewModelScope.launch { repository.setMode(mode) }
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
