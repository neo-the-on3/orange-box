package io.github.neotheon3.orangebox.feature.driving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.dashboardui.BottomBarButton
import io.github.neotheon3.orangebox.core.dashboardui.BottomBarButtonSize
import io.github.neotheon3.orangebox.core.dashboardui.BottomBarToggle
import io.github.neotheon3.orangebox.core.dashboardui.DashboardColors
import io.github.neotheon3.orangebox.core.dashboardui.DashboardIcons

/**
 * Bottom bar (§4.5): Emergency (hazard) far left, Diagnostics shortcut where the legacy map launcher
 * was, the Detailed Info toggle in the middle, the old quick-call slot left empty (D2), End driving
 * (power) far right. Every target is ≥ 56 dp.
 */
@Composable
fun DashboardBottomBar(
    showDetail: Boolean,
    landscape: Boolean,
    onToggleDetail: () -> Unit,
    onEmergency: () -> Unit,
    onDiagnostics: () -> Unit,
    onEndDriving: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("dashboard_bottom_bar"),
        horizontalArrangement = Arrangement.spacedBy(if (landscape) 16.dp else 8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomBarButton(
            DashboardIcons.Hazard, stringResource(R.string.dashboard_emergency), onEmergency,
            Modifier.testTag("dashboard_emergency"), iconTint = DashboardColors.HazardRed,
        )
        if (landscape) Spacer(Modifier.weight(1f))
        BottomBarButton(
            DashboardIcons.MilEngine, stringResource(R.string.dashboard_diagnostics), onDiagnostics,
            Modifier.testTag("dashboard_diagnostics"),
        )
        BottomBarToggle(
            label = stringResource(R.string.dashboard_detailed_info),
            selected = showDetail,
            onClick = onToggleDetail,
            stateOn = stringResource(R.string.dashboard_detail_shown),
            stateOff = stringResource(R.string.dashboard_detail_hidden),
            modifier = (if (landscape) Modifier.widthIn(min = 150.dp, max = 240.dp) else Modifier.weight(1f))
                .testTag("dashboard_detail_toggle"),
        )
        // Legacy quick-call slot: intentionally empty on phones (decision D2).
        if (landscape) Spacer(Modifier.size(BottomBarButtonSize))
        if (landscape) Spacer(Modifier.weight(1f))
        BottomBarButton(
            DashboardIcons.Power, stringResource(R.string.dashboard_end_driving), onEndDriving,
            Modifier.testTag("dashboard_end_driving"),
        )
    }
}
