package io.github.neotheon3.orangebox.feature.driving

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.dashboardui.DashboardStateMapper
import io.github.neotheon3.orangebox.core.dashboardui.DashboardUiState
import io.github.neotheon3.orangebox.core.driving.DriveSession
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.pid.PidPoller
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    connection: ObdConnection,
    telemetry: TelemetrySource,
    private val poller: PidPoller,
    private val session: DriveSession,
) : ViewModel() {
    val state: StateFlow<DashboardUiState> = combine(connection.state, telemetry.latest) { c, s ->
        DashboardStateMapper.map(c, s, poller.activePids)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun endDriving() = session.requestEnd()
}
