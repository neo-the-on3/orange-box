package io.github.neotheon3.orangebox.feature.driving

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.dashboardui.DashboardStateMapper
import io.github.neotheon3.orangebox.core.dashboardui.DashboardUiState
import io.github.neotheon3.orangebox.core.driving.DriveSession
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.pid.PidPoller
import io.github.neotheon3.orangebox.core.telemetry.RecordedTripTotals
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.graphics.asImageBitmap
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.driving.DeviceCountry
import io.github.neotheon3.orangebox.core.driving.QuickLaunchApps
import io.github.neotheon3.orangebox.core.model.DashboardButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    connection: ObdConnection,
    telemetry: TelemetrySource,
    private val poller: PidPoller,
    private val session: DriveSession,
    prefs: AppPreferencesRepository,
    private val country: DeviceCountry,
    private val apps: QuickLaunchApps,
    recorded: RecordedTripTotals,
) : ViewModel() {
    val state: StateFlow<DashboardUiState> = combine(connection.state, telemetry.latest, recorded.totals()) { c, s, trip ->
        DashboardStateMapper.map(c, s, poller.activePids, recordedTrip = trip)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    val controls: StateFlow<DashboardControls> = prefs.preferences.map { it.dashboard }.distinctUntilChanged()
        .map { d ->
            val app = d.quickLaunchPackage?.takeIf { d.button == DashboardButton.QUICK_LAUNCHER }
                ?.let { withContext(Dispatchers.IO) { apps.find(it) } }
            DashboardControls(
                // A quick-launch app that is gone (or not chosen yet) falls back to the hazard button.
                leftButton = app?.let { LeftButton.QuickLaunch(it.packageName, it.label, it.icon?.asImageBitmap()) }
                    ?: LeftButton.Hazard(country.emergencyNumber(d)),
                showWhenLocked = d.autoShowDashboard,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardControls())

    fun endDriving() = session.requestEnd()

    fun quickLaunch(packageName: String) {
        if (!apps.launch(packageName)) OrangeLog.w(OrangeLog.Areas.APP, "quick_launch_failed", "package" to packageName)
    }
}
