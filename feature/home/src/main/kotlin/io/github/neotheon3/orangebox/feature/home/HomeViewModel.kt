package io.github.neotheon3.orangebox.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val backend: BackendConfig = BackendConfig(),
    val loading: Boolean = true,
) {
    val showRemoteWarning: Boolean
        get() = !loading && backend.mode == io.github.neotheon3.orangebox.core.model.BackendMode.REMOTE && !backend.isRemoteReady
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    backendConfigRepository: BackendConfigRepository,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = backendConfigRepository.config
        .map { HomeUiState(backend = it, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
