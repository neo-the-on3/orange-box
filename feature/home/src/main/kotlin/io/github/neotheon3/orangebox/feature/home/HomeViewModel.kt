package io.github.neotheon3.orangebox.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val backend: BackendConfig = BackendConfig(),
    val loading: Boolean = true,
    val obd: ObdConnectionState = ObdConnectionState.Idle,
) {
    val showRemoteWarning: Boolean
        get() = !loading && backend.mode == BackendMode.REMOTE && !backend.isRemoteReady
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    backendConfigRepository: BackendConfigRepository,
    private val obd: ObdConnection,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(backendConfigRepository.config, obd.state) { backend, obdState ->
        HomeUiState(backend = backend, loading = false, obd = obdState)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onConnect() = obd.connect()
    fun onRetry() = obd.retry()
    fun onDisconnect() = obd.disconnect()
}
