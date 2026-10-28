package io.github.neotheon3.orangebox.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.driving.LaunchableApp
import io.github.neotheon3.orangebox.core.model.DashboardButton
import io.github.neotheon3.orangebox.core.model.DashboardPreferences
import kotlin.math.roundToInt

/** Callbacks of the "Driving dashboard" section. */
data class DashboardSettingsActions(
    val onButtonChanged: (DashboardButton) -> Unit = {},
    val onEmergencyNumberChanged: (String) -> Unit = {},
    val onPickApp: () -> Unit = {},
    val onAppChosen: (String) -> Unit = {},
    val onOverspeedChanged: (Int) -> Unit = {},
    val onSpeedometerOffsetChanged: (Int) -> Unit = {},
    val onAutoShowChanged: (Boolean) -> Unit = {},
    val onOpenFullScreenSettings: () -> Unit = {},
)

/**
 * "Driving dashboard" settings (Material 3): what the bottom-left button does (hazard with an editable
 * number, or a quick launcher app), the overspeed threshold and "Show dashboard automatically".
 */
@Composable
fun DashboardSettingsSection(
    state: DashboardSettingsState,
    apps: List<LaunchableApp>?,
    actions: DashboardSettingsActions,
    units: io.github.neotheon3.orangebox.core.model.UnitPreferences = io.github.neotheon3.orangebox.core.model.UnitPreferences(),
) {
    val prefs = state.prefs
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_dashboard_section), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_dashboard_button), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup()) {
            ButtonChoice(
                DashboardButton.HAZARD, prefs.button, R.string.settings_button_hazard, R.string.settings_button_hazard_summary,
                actions.onButtonChanged,
            )
            ButtonChoice(
                DashboardButton.QUICK_LAUNCHER, prefs.button, R.string.settings_button_launcher, R.string.settings_button_launcher_summary,
            ) {
                actions.onButtonChanged(it)
                if (prefs.quickLaunchPackage == null) { picking = true; actions.onPickApp() }
            }
        }
        when (prefs.button) {
            DashboardButton.HAZARD -> EmergencyNumberField(state, actions.onEmergencyNumberChanged)
            DashboardButton.QUICK_LAUNCHER -> ListItem(
                leadingContent = { AppIcon(state.launchApp) },
                headlineContent = { Text(stringResource(R.string.settings_launcher_app)) },
                supportingContent = {
                    Text(
                        state.launchApp?.label ?: stringResource(
                            if (prefs.quickLaunchPackage == null) R.string.settings_launcher_choose else R.string.settings_launcher_missing,
                        ),
                    )
                },
                modifier = Modifier.clickable(role = Role.Button) { picking = true; actions.onPickApp() }.testTag("settings_launcher_app"),
            )
        }
        OverspeedSlider(prefs.overspeedKph, actions.onOverspeedChanged, units)
        SpeedometerOffsetSlider(prefs.speedometerOffsetPercent, actions.onSpeedometerOffsetChanged)
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_auto_show)) },
            supportingContent = { Text(stringResource(R.string.settings_auto_show_summary)) },
            trailingContent = { Switch(checked = prefs.autoShowDashboard, onCheckedChange = null) },
            modifier = Modifier
                .toggleable(value = prefs.autoShowDashboard, onValueChange = actions.onAutoShowChanged, role = Role.Switch)
                .testTag("settings_auto_show"),
        )
        if (prefs.autoShowDashboard && !state.fullScreenAllowed) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_full_screen_needed)) },
                supportingContent = { Text(stringResource(R.string.settings_full_screen_needed_summary)) },
                trailingContent = {
                    FilledTonalButton(onClick = actions.onOpenFullScreenSettings, modifier = Modifier.testTag("settings_full_screen_allow")) {
                        Text(stringResource(R.string.settings_full_screen_allow))
                    }
                },
                modifier = Modifier.testTag("settings_full_screen"),
            )
        }
    }
    if (picking) {
        AppPickerDialog(
            apps = apps,
            selected = prefs.quickLaunchPackage,
            onChosen = { picking = false; actions.onAppChosen(it) },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ButtonChoice(value: DashboardButton, selected: DashboardButton, title: Int, summary: Int, onSelect: (DashboardButton) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton)
            .padding(vertical = 6.dp)
            .testTag("settings_button_${value.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(title))
            Text(stringResource(summary), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmergencyNumberField(state: DashboardSettingsState, onChanged: (String) -> Unit) {
    val saved = state.prefs.emergencyNumber ?: state.defaultEmergencyNumber
    var draft by rememberSaveable(saved) { mutableStateOf(saved) }
    val valid = draft.isBlank() || DashboardPreferences.isValidEmergencyNumber(draft.trim())
    val country = state.country ?: stringResource(R.string.settings_country_unknown)
    Column {
        OutlinedTextField(
            value = draft,
            onValueChange = { v ->
                draft = v.filter { it.isDigit() || it in "+*#" }.take(15)
                // Blank is a draft, not a choice: "Use default" is how the country default comes back.
                if (draft.isNotBlank()) onChanged(draft)
            },
            label = { Text(stringResource(R.string.settings_emergency_number)) },
            singleLine = true,
            isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            supportingText = {
                Text(
                    if (valid) stringResource(R.string.settings_emergency_number_help, country, state.defaultEmergencyNumber)
                    else stringResource(R.string.settings_emergency_number_invalid),
                )
            },
            modifier = Modifier.fillMaxWidth().testTag("settings_emergency_number"),
        )
        if (state.prefs.emergencyNumber != null) {
            TextButton(
                onClick = { draft = state.defaultEmergencyNumber; onChanged("") },
                modifier = Modifier.testTag("settings_emergency_default"),
            ) { Text(stringResource(R.string.settings_emergency_use_default, state.defaultEmergencyNumber)) }
        }
    }
}

@Composable
private fun OverspeedSlider(kph: Int, onChanged: (Int) -> Unit, units: io.github.neotheon3.orangebox.core.model.UnitPreferences) {
    var value by remember(kph) { mutableFloatStateOf(kph.toFloat()) }
    val shown = value.roundToInt()
    // Stored and stepped in km/h (5 km/h steps); shown in the Settings unit (#58).
    fun shownIn(k: Int) = units.speed(k.toDouble()).roundToInt()
    val label = units.speedLabel
    val summary = stringResource(
        R.string.settings_overspeed_summary, shownIn(shown), shownIn(shown - DashboardPreferences.OVERSPEED_HYSTERESIS_KPH), label,
    )
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_overspeed)) },
            supportingContent = { Text(summary) },
            trailingContent = { Text(stringResource(R.string.settings_overspeed_value, shownIn(shown), label), style = MaterialTheme.typography.titleMedium) },
        )
        val min = DashboardPreferences.MIN_OVERSPEED_KPH
        val max = DashboardPreferences.MAX_OVERSPEED_KPH
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChanged(value.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min) / DashboardPreferences.OVERSPEED_STEP_KPH - 1,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = summary; stateDescription = "${shownIn(shown)} $label" }
                .testTag("settings_overspeed"),
        )
    }
}

/**
 * "Speedometer offset" (#210): Orange Box shows the true (ECU/GPS) speed, which is a few % below the car's
 * speedometer. Optionally show the speed that much higher on the dashboard; display only.
 */
@Composable
private fun SpeedometerOffsetSlider(percent: Int, onChanged: (Int) -> Unit) {
    var value by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    val shown = value.roundToInt()
    val summary = stringResource(R.string.settings_speedometer_offset_summary)
    Column {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_speedometer_offset)) },
            supportingContent = { Text(summary) },
            trailingContent = {
                Text(
                    if (shown == 0) stringResource(R.string.settings_speedometer_offset_off) else stringResource(R.string.settings_speedometer_offset_value, shown),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("settings_speedometer_offset_value"),
                )
            },
        )
        val max = DashboardPreferences.MAX_SPEEDOMETER_OFFSET_PERCENT
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChanged(value.roundToInt()) },
            valueRange = 0f..max.toFloat(),
            steps = max - 1,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = summary; stateDescription = "+$shown %" }
                .testTag("settings_speedometer_offset"),
        )
    }
}

@Composable
private fun AppIcon(app: LaunchableApp?) {
    val bitmap = remember(app?.icon) { app?.icon?.asImageBitmap() }
    if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
}

@Composable
private fun AppPickerDialog(apps: List<LaunchableApp>?, selected: String?, onChosen: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("settings_app_picker"),
        title = { Text(stringResource(R.string.settings_launcher_pick_title)) },
        text = {
            when {
                apps == null -> CircularProgressIndicator()
                apps.isEmpty() -> Text(stringResource(R.string.settings_launcher_none))
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        ListItem(
                            leadingContent = { AppIcon(app) },
                            headlineContent = { Text(app.label) },
                            trailingContent = { RadioButton(selected = app.packageName == selected, onClick = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier
                                .selectable(selected = app.packageName == selected, onClick = { onChosen(app.packageName) }, role = Role.RadioButton)
                                .testTag("settings_app_${app.packageName}"),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/** Keeps the full-screen check fresh after a round trip to system settings. */
@Composable
internal fun RefreshOnResume(onResume: () -> Unit) {
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        onResume()
        onPauseOrDispose { }
    }
}
