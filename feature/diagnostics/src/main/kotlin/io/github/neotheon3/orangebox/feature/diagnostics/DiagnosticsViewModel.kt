package io.github.neotheon3.orangebox.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.database.DtcRecordEntity
import io.github.neotheon3.orangebox.core.diagnostics.ClearResult
import io.github.neotheon3.orangebox.core.diagnostics.DiagnosticsReport
import io.github.neotheon3.orangebox.core.diagnostics.DiagnosticsRepository
import io.github.neotheon3.orangebox.core.diagnostics.ScanResult
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A one-off result shown in the message bar until the next action. */
enum class DiagnosticsNotice { SCAN_FAILED, NOT_CONNECTED, CLEARED, CLEAR_REJECTED, CLEAR_FAILED, WHILE_MOVING }

data class DiagnosticsUiState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val report: DiagnosticsReport? = null,
    /** Speed > 0: Clear shows "stop the car first" instead of the confirmation. */
    val moving: Boolean = false,
    val history: List<DtcRecordEntity> = emptyList(),
    val notice: DiagnosticsNotice? = null,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticsRepository,
    connection: ObdConnection,
    telemetry: TelemetrySource,
) : ViewModel() {
    private val notice = MutableStateFlow<DiagnosticsNotice?>(null)

    val state: StateFlow<DiagnosticsUiState> = combine(
        connection.state.map { it is ObdConnectionState.Connected }.distinctUntilChanged(),
        repository.busy,
        repository.latest,
        combine(telemetry.latest.map { (it?.instant?.speedKph ?: 0.0) > 0.0 }.distinctUntilChanged(), repository.history, ::Pair),
        notice,
    ) { connected, busy, report, (moving, history), n ->
        DiagnosticsUiState(connected, busy, report, moving, history, n)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    init {
        // Read on open, and again whenever the car (re)connects while the screen is up (legacy behaviour).
        viewModelScope.launch {
            connection.state.map { it is ObdConnectionState.Connected }.distinctUntilChanged().filter { it }.collect {
                val last = repository.latest.value
                if (last == null || !repository.busy.value) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            notice.value = null
            notice.value = when (repository.scan()) {
                is ScanResult.Success -> null
                ScanResult.NotConnected -> DiagnosticsNotice.NOT_CONNECTED
                is ScanResult.Failed -> DiagnosticsNotice.SCAN_FAILED
            }
        }
    }

    /** Only called after the user confirmed. */
    fun clear() {
        viewModelScope.launch {
            notice.value = null
            notice.value = when (repository.clear()) {
                is ClearResult.Cleared -> DiagnosticsNotice.CLEARED
                ClearResult.NotConnected -> DiagnosticsNotice.NOT_CONNECTED
                ClearResult.WhileMoving -> DiagnosticsNotice.WHILE_MOVING
                ClearResult.Rejected -> DiagnosticsNotice.CLEAR_REJECTED
                is ClearResult.Failed -> DiagnosticsNotice.CLEAR_FAILED
            }
        }
    }
}
