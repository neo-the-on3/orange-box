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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.RecordedTripTotals
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import javax.inject.Inject
import io.github.neotheon3.orangebox.core.dashboardui.DashboardStateMapper
import io.github.neotheon3.orangebox.core.dashboardui.DashboardUiState

data class HomeUiState(
    val backend: BackendConfig = BackendConfig(),
    val loading: Boolean = true,
    val obd: ObdConnectionState = ObdConnectionState.Idle,
    val live: TelemetrySnapshot? = null,
    /** Same mapping as the driving dashboard, so Home and dashboard show identical values. */
    val dashboard: DashboardUiState = DashboardUiState(),
) {
    /** "Backend: Fake/Remote"; hidden while the backend is Off (the default), see #192. */
    val showBackendPill: Boolean
        get() = !loading && backend.mode != BackendMode.OFF

    val showRemoteWarning: Boolean
        get() = !loading && backend.mode == BackendMode.REMOTE && !backend.isRemoteReady
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    backendConfigRepository: BackendConfigRepository,
    private val obd: ObdConnection,
    telemetry: TelemetrySource,
    recorded: RecordedTripTotals = RecordedTripTotals.NONE,
    units: io.github.neotheon3.orangebox.core.datastore.DisplayUnits = io.github.neotheon3.orangebox.core.datastore.DisplayUnits.METRIC,
    prefs: io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository? = null,
) : ViewModel() {
    /** The dashboard's display-only speedometer offset (#210), so the Home card shows the same number. */
    private val speedometerOffset: kotlinx.coroutines.flow.Flow<Int> =
        prefs?.preferences?.map { it.dashboard.speedometerOffsetPercent }?.distinctUntilChanged() ?: kotlinx.coroutines.flow.flowOf(0)

    val uiState: StateFlow<HomeUiState> =
        combine(
            combine(backendConfigRepository.config, obd.state, telemetry.latest, ::Triple),
            recorded.totals(), units.units(), speedometerOffset,
        ) { (backend, obdState, live), trip, u, offset ->
            HomeUiState(
                backend = backend, loading = false, obd = obdState, live = live,
                dashboard = DashboardStateMapper.map(obdState, live, recordedTrip = trip, units = u, speedometerOffsetPercent = offset),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onConnect() = obd.connect()
    fun onRetry() = obd.retry()
    fun onDisconnect() = obd.disconnect()
}
