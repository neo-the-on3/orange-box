package io.github.neotheon3.orangebox.feature.driving

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neotheon3.orangebox.core.dashboardui.BezelBackground
import io.github.neotheon3.orangebox.core.dashboardui.ConnectChips
import io.github.neotheon3.orangebox.core.dashboardui.DashboardSettings
import io.github.neotheon3.orangebox.core.dashboardui.DashboardTheme
import io.github.neotheon3.orangebox.core.dashboardui.DashboardUiState
import io.github.neotheon3.orangebox.core.dashboardui.FuelCard
import io.github.neotheon3.orangebox.core.dashboardui.LocalDashboardSettings
import io.github.neotheon3.orangebox.core.dashboardui.RpmCluster
import io.github.neotheon3.orangebox.core.dashboardui.SpeedCluster
import io.github.neotheon3.orangebox.core.dashboardui.TripCard
import io.github.neotheon3.orangebox.core.dashboardui.rememberReduceMotion

/** Detailed Info toggle duration (legacy: 500 ms slide + fade). */
const val DETAIL_TRANSITION_MILLIS = 500

@Composable
fun DashboardRoute(onEndDriving: () -> Unit, onBack: () -> Unit, viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()
    KeepScreenOn()
    ShowWhenLocked(controls.showWhenLocked)
    BackHandler(onBack = onBack)
    DashboardScreen(
        state,
        onEndDriving = { viewModel.endDriving(); onEndDriving() },
        leftButton = controls.leftButton,
        onQuickLaunch = viewModel::quickLaunch,
    )
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

private enum class DashboardDialog { EMERGENCY, DIAGNOSTICS }

/**
 * Driving dashboard (docs/ui/LEGACY_OBD_UI.md §4): RPM cluster, centre column (MIL/OBD, FUEL, TRIP) and
 * speed cluster in landscape; stacked in portrait. "Detailed Info." swaps the clusters for the detail
 * grid with a 500 ms slide/fade. Always dark, edge to edge.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onEndDriving: () -> Unit,
    modifier: Modifier = Modifier,
    initialShowDetail: Boolean = false,
    settings: DashboardSettings = DashboardSettings(reduceMotion = rememberReduceMotion()),
    leftButton: LeftButton = LeftButton.Hazard(EMERGENCY_NUMBER),
    onQuickLaunch: (String) -> Unit = {},
) {
    var showDetail by rememberSaveable { mutableStateOf(initialShowDetail) }
    var dialog by rememberSaveable { mutableStateOf<DashboardDialog?>(null) }
    LightSystemBarIcons()
    DashboardTheme(settings) {
        BezelBackground(modifier.fillMaxSize().testTag("dashboard")) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val landscape = maxWidth > maxHeight
                Column(Modifier.fillMaxSize()) {
                    DashboardBody(state, showDetail, landscape, Modifier.weight(1f).fillMaxWidth())
                    DashboardBottomBar(
                        showDetail = showDetail,
                        landscape = landscape,
                        onToggleDetail = { showDetail = !showDetail },
                        onEmergency = { dialog = DashboardDialog.EMERGENCY },
                        onDiagnostics = { dialog = DashboardDialog.DIAGNOSTICS },
                        onEndDriving = onEndDriving,
                        leftButton = leftButton,
                        onQuickLaunch = onQuickLaunch,
                    )
                }
            }
            when (dialog) {
                DashboardDialog.EMERGENCY -> EmergencyPromptDialog(
                    onDismiss = { dialog = null },
                    number = (leftButton as? LeftButton.Hazard)?.number ?: EMERGENCY_NUMBER,
                )
                DashboardDialog.DIAGNOSTICS -> DiagnosticsStubDialog(state, onDismiss = { dialog = null })
                null -> Unit
            }
        }
    }
}

@Composable
private fun DashboardBody(state: DashboardUiState, showDetail: Boolean, landscape: Boolean, modifier: Modifier) {
    val reduceMotion = LocalDashboardSettings.current.reduceMotion
    val progress by animateFloatAsState(
        if (showDetail) 1f else 0f,
        if (reduceMotion) snap() else tween(DETAIL_TRANSITION_MILLIS),
        label = "detailTransition",
    )
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        if (progress < 1f) {
            if (landscape) {
                LandscapeClusters(state, progress, width, maxWidth, maxHeight)
            } else {
                PortraitClusters(state, progress, width, maxHeight)
            }
        }
        if (progress > 0f) {
            DetailGrid(
                state, landscape,
                Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 6.dp).graphicsLayer { alpha = progress },
            )
        }
    }
}

private fun meterMax(clusterHeight: Dp) = (clusterHeight.value * 0.34f).coerceIn(36f, 120f).sp

@Composable
private fun LandscapeClusters(state: DashboardUiState, progress: Float, widthPx: Float, maxW: Dp, maxH: Dp) {
    val gap = 8.dp
    val centreW = 124.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
    val avail = maxH - 12.dp
    val clusterW = min((maxW - centreW - gap * 2 - 16.dp) / 2, avail * 1.25f)
    val clusterH = min(avail, clusterW * 1.12f)
    val meter = meterMax(clusterH)
    Row(
        Modifier.fillMaxSize().padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RpmCluster(
            state,
            Modifier.size(clusterW, clusterH).graphicsLayer { translationX = -progress * widthPx },
            meterMax = meter,
        )
        Column(
            // The centre column may be taller than the clusters (legacy: 214 vs 232 dp, the other way round).
            Modifier.width(centreW).height(avail).graphicsLayer { alpha = 1f - progress },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConnectChips(state, Modifier.fillMaxWidth())
            FuelCard(state, Modifier.fillMaxWidth().weight(1.15f))
            TripCard(state, Modifier.fillMaxWidth().weight(1f))
        }
        SpeedCluster(
            state,
            Modifier.size(clusterW, clusterH).graphicsLayer { translationX = progress * widthPx },
            meterMax = meter,
        )
    }
}

@Composable
private fun PortraitClusters(state: DashboardUiState, progress: Float, widthPx: Float, maxH: Dp) {
    // Clusters share whatever the cards and chips leave, up to 260 dp each.
    val meter = meterMax(((maxH - 260.dp) / 2).coerceIn(120.dp, 260.dp))
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        RpmCluster(
            state,
            Modifier.fillMaxWidth().weight(1f).heightIn(max = 260.dp).graphicsLayer { translationX = -progress * widthPx },
            meterMax = meter,
        )
        SpeedCluster(
            state,
            Modifier.fillMaxWidth().weight(1f).heightIn(max = 260.dp).graphicsLayer { translationX = progress * widthPx },
            meterMax = meter,
        )
        Row(
            Modifier.fillMaxWidth().height(112.dp * LocalDensity.current.fontScale).graphicsLayer { alpha = 1f - progress },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FuelCard(state, Modifier.weight(1f).fillMaxHeight())
            TripCard(state, Modifier.weight(1f).fillMaxHeight())
        }
        Box(Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - progress }, contentAlignment = Alignment.Center) {
            ConnectChips(state, Modifier.width(180.dp))
        }
    }
}
