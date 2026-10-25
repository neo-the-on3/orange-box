package io.github.neotheon3.orangebox.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.dashboardui.DashboardIcons
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState

/** What the Home Drive button does in each connection state (#192). */
enum class DriveAction {
    /** Not connected: connect, then open the dashboard (it shows "connecting" until data arrives). */
    CONNECT_AND_DRIVE,

    /** Connecting or connected: just open the dashboard. */
    OPEN_DASHBOARD,
    ;

    companion object {
        fun of(state: ObdConnectionState): DriveAction = when (state) {
            ObdConnectionState.Idle, ObdConnectionState.LinkLost, is ObdConnectionState.Failed -> CONNECT_AND_DRIVE
            is ObdConnectionState.Connecting, is ObdConnectionState.Initializing,
            is ObdConnectionState.WaitingToRetry, is ObdConnectionState.Connected,
            -> OPEN_DASHBOARD
        }
    }
}

/**
 * The big Drive button at the top of Home, in every connection state (#192): the dashboard was hard to find
 * because its only entry point was a small button inside the Live data card, which only shows once connected.
 */
@Composable
fun DriveButton(
    state: ObdConnectionState,
    onConnect: () -> Unit,
    onOpenDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = DriveAction.of(state)
    Button(
        onClick = {
            if (action == DriveAction.CONNECT_AND_DRIVE) onConnect()
            onOpenDashboard()
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("home_drive"),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Icon(DashboardIcons.OverspeedGauge, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (action == DriveAction.CONNECT_AND_DRIVE) R.string.home_drive_start else R.string.home_open_dashboard),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(
                        when {
                            action == DriveAction.CONNECT_AND_DRIVE -> R.string.home_drive_hint_connect
                            state is ObdConnectionState.Connected -> R.string.home_drive_hint_connected
                            else -> R.string.home_drive_hint_connecting
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("home_drive_hint"),
                )
            }
        }
    }
}
