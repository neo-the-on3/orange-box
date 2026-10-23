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
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import javax.inject.Inject

data class HomeUiState(
    val backend: BackendConfig = BackendConfig(),
    val loading: Boolean = true,
    val obd: ObdConnectionState = ObdConnectionState.Idle,
    val live: TelemetrySnapshot? = null,
) {
    val showRemoteWarning: Boolean
        get() = !loading && backend.mode == BackendMode.REMOTE && !backend.isRemoteReady
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    backendConfigRepository: BackendConfigRepository,
    private val obd: ObdConnection,
    telemetry: TelemetrySource,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(backendConfigRepository.config, obd.state, telemetry.latest) { backend, obdState, live ->
        HomeUiState(backend = backend, loading = false, obd = obdState, live = live)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onConnect() = obd.connect()
    fun onRetry() = obd.retry()
    fun onDisconnect() = obd.disconnect()
}
