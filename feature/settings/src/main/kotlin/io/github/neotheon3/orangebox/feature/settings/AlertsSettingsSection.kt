package io.github.neotheon3.orangebox.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.neotheon3.orangebox.core.model.AlertPreferences

/** "Driving alerts" (#59): each alert on its own switch, then how they play (tone and/or voice). */
@Composable
fun AlertsSettingsSection(alerts: AlertPreferences, onChanged: (AlertPreferences) -> Unit) {
    Column {
        Text(stringResource(R.string.settings_alerts_section), style = MaterialTheme.typography.titleMedium)
        SwitchRow(R.string.settings_alert_overspeed, R.string.settings_alert_overspeed_summary, alerts.overspeed, "settings_alert_overspeed") {
            onChanged(alerts.copy(overspeed = it))
        }
        SwitchRow(R.string.settings_alert_harsh, R.string.settings_alert_harsh_summary, alerts.harsh, "settings_alert_harsh") {
            onChanged(alerts.copy(harsh = it))
        }
        SwitchRow(R.string.settings_alert_idling, R.string.settings_alert_idling_summary, alerts.idling, "settings_alert_idling") {
            onChanged(alerts.copy(idling = it))
        }
        SwitchRow(R.string.settings_alert_mil, R.string.settings_alert_mil_summary, alerts.mil, "settings_alert_mil") {
            onChanged(alerts.copy(mil = it))
        }
        SwitchRow(R.string.settings_alert_sound, R.string.settings_alert_sound_summary, alerts.sound, "settings_alert_sound") {
            onChanged(alerts.copy(sound = it))
        }
        SwitchRow(R.string.settings_alert_voice, R.string.settings_alert_voice_summary, alerts.voice, "settings_alert_voice") {
            onChanged(alerts.copy(voice = it))
        }
    }
}

/** A Material 3 list row with a trailing switch; the whole row toggles. */
@Composable
internal fun SwitchRow(title: Int, summary: Int?, checked: Boolean, tag: String, onChanged: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = summary?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.testTag(tag).toggleable(value = checked, role = Role.Switch, onValueChange = onChanged),
    )
}
