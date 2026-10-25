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

/** Fallback number pre-filled in the dialer (112: India, the EU, and GSM phones elsewhere). Settings can change it. */
const val EMERGENCY_NUMBER = "112"

/**
 * Emergency prompt, manual mode. A stub until crash detection and emergency contacts land (M7, #54/#55):
 * it opens the phone's dialer with [number] pre-filled; nothing is called automatically.
 */
@Composable
fun EmergencyPromptDialog(onDismiss: () -> Unit, number: String = EMERGENCY_NUMBER) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("emergency_dialog"),
        icon = { Icon(DashboardIcons.Hazard, contentDescription = null, tint = DashboardColors.HazardRed) },
        title = { Text(stringResource(R.string.emergency_title)) },
        text = { Text(stringResource(R.string.emergency_body, number)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.emergency_no_dialer, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.testTag("emergency_dial"),
            ) { Text(stringResource(R.string.emergency_dial, number)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) } },
    )
}
