package io.github.neotheon3.orangebox.feature.driving

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.neotheon3.orangebox.core.dashboardui.DashboardColors
import io.github.neotheon3.orangebox.core.dashboardui.DashboardIcons
import io.github.neotheon3.orangebox.core.dashboardui.DashboardUiState

/** Number pre-filled in the dialer. 112 reaches emergency services in India and the EU, and on GSM phones elsewhere. */
const val EMERGENCY_NUMBER = "112"

/**
 * Emergency prompt, manual mode. A stub until crash detection and emergency contacts land (M7, #54/#55):
 * it opens the phone's dialer with [EMERGENCY_NUMBER] pre-filled; nothing is called automatically.
 */
@Composable
fun EmergencyPromptDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("emergency_dialog"),
        icon = { Icon(DashboardIcons.Hazard, contentDescription = null, tint = DashboardColors.HazardRed) },
        title = { Text(stringResource(R.string.emergency_title)) },
        text = { Text(stringResource(R.string.emergency_body, EMERGENCY_NUMBER)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$EMERGENCY_NUMBER")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.emergency_no_dialer, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.testTag("emergency_dial"),
            ) { Text(stringResource(R.string.emergency_dial, EMERGENCY_NUMBER)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) } },
    )
}

/** Diagnostics shortcut: MIL state and stored-code count now; the full Diagnostics screen comes in M6. */
@Composable
fun DiagnosticsStubDialog(state: DashboardUiState, onDismiss: () -> Unit) {
    val mil = stringResource(if (state.milOn) R.string.diagnostics_mil_on else R.string.diagnostics_mil_off)
    val codes = state.dtcCount?.toString() ?: stringResource(R.string.diagnostics_unknown)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("diagnostics_dialog"),
        icon = { Icon(DashboardIcons.MilEngine, contentDescription = null) },
        title = { Text(stringResource(R.string.dashboard_diagnostics)) },
        text = { Text(stringResource(R.string.diagnostics_body, mil, codes)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) } },
    )
}
