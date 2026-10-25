package io.github.neotheon3.orangebox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.model.CrashPreferences
import kotlin.math.roundToInt

/** Settings → "Crash detection" state: the stored settings and the manual contact entry in progress. */
data class CrashSettingsState(
    val prefs: CrashPreferences = CrashPreferences(),
    /** The number being typed; null when not editing. */
    val contactDraft: String? = null,
    /** The typed or picked number was not usable. */
    val contactError: Boolean = false,
)

data class CrashSettingsActions(
    val onChanged: (CrashPreferences) -> Unit = {},
    val onPickContact: () -> Unit = {},
    val onEditContact: () -> Unit = {},
    val onContactDraftChanged: (String) -> Unit = {},
    val onSaveContact: () -> Unit = {},
    val onCancelContact: () -> Unit = {},
    val onClearContact: () -> Unit = {},
)

/**
 * "Crash detection" (M7 settings + the emergency contact, M8 #60): detection on/off, sensitivity, the
 * countdown, and who the "text my location" button goes to. The call button always dials the
 * dashboard's emergency number.
 */
@Composable
fun CrashSettingsSection(state: CrashSettingsState, actions: CrashSettingsActions) {
    val prefs = state.prefs
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_crash_section), style = MaterialTheme.typography.titleMedium)
        SwitchRow(R.string.settings_crash_detection, R.string.settings_crash_detection_summary, prefs.detectionEnabled, "settings_crash_detection") {
            actions.onChanged(prefs.copy(detectionEnabled = it))
        }
        if (prefs.detectionEnabled) {
            StepSlider(
                title = stringResource(R.string.settings_crash_sensitivity),
                value = prefs.thresholdG.toFloat(),
                range = CrashPreferences.MIN_THRESHOLD_G.toFloat()..CrashPreferences.MAX_THRESHOLD_G.toFloat(),
                step = 0.5f,
                label = { stringResource(R.string.settings_crash_threshold_value, it) },
                summary = { g ->
                    stringResource(
                        when {
                            g < 3.5f -> R.string.settings_crash_sensitivity_high
                            g <= 5f -> R.string.settings_crash_sensitivity_normal
                            else -> R.string.settings_crash_sensitivity_low
                        },
                    )
                },
                tag = "settings_crash_threshold",
            ) { actions.onChanged(prefs.copy(thresholdG = it.toDouble())) }
            StepSlider(
                title = stringResource(R.string.settings_crash_countdown),
                value = prefs.countdownSeconds.toFloat(),
                range = CrashPreferences.MIN_COUNTDOWN_S.toFloat()..CrashPreferences.MAX_COUNTDOWN_S.toFloat(),
                step = 5f,
                label = { stringResource(R.string.settings_crash_countdown_value, it.roundToInt()) },
                summary = { stringResource(R.string.settings_crash_countdown_summary) },
                tag = "settings_crash_countdown",
            ) { actions.onChanged(prefs.copy(countdownSeconds = it.roundToInt())) }
        }
        EmergencyContact(state, actions)
    }
}

@Composable
private fun EmergencyContact(state: CrashSettingsState, actions: CrashSettingsActions) {
    val number = state.prefs.contactNumber
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_crash_contact)) },
        supportingContent = {
            Text(
                when {
                    number == null -> stringResource(R.string.settings_crash_contact_none)
                    state.prefs.contactName != null -> "${state.prefs.contactName} · $number"
                    else -> number
                },
            )
        },
        modifier = Modifier.testTag("settings_crash_contact"),
    )
    val draft = state.contactDraft
    if (draft != null) {
        OutlinedTextField(
            value = draft,
            onValueChange = actions.onContactDraftChanged,
            label = { Text(stringResource(R.string.settings_crash_contact_number)) },
            singleLine = true,
            isError = state.contactError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            supportingText = {
                Text(stringResource(if (state.contactError) R.string.settings_crash_contact_invalid else R.string.settings_crash_contact_help))
            },
            modifier = Modifier.fillMaxWidth().testTag("settings_crash_contact_field"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onSaveContact, modifier = Modifier.testTag("settings_crash_contact_save")) {
                Text(stringResource(R.string.settings_crash_contact_save))
            }
            TextButton(onClick = actions.onCancelContact) { Text(stringResource(R.string.settings_cancel)) }
        }
    } else {
        if (state.contactError) {
            Text(
                stringResource(R.string.settings_crash_contact_picked_invalid),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onPickContact, modifier = Modifier.testTag("settings_crash_contact_pick")) {
                Text(stringResource(R.string.settings_crash_contact_pick))
            }
            OutlinedButton(onClick = actions.onEditContact, modifier = Modifier.testTag("settings_crash_contact_edit")) {
                Text(stringResource(R.string.settings_crash_contact_enter))
            }
        }
        if (number != null) {
            TextButton(onClick = actions.onClearContact, modifier = Modifier.testTag("settings_crash_contact_clear")) {
                Text(stringResource(R.string.settings_crash_contact_clear))
            }
        }
    }
    Text(stringResource(R.string.settings_crash_contact_privacy), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StepSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    label: @Composable (Float) -> String,
    summary: @Composable (Float) -> String,
    tag: String,
    onChanged: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    val shown = label(current)
    val description = summary(current)
    Column {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
            trailingContent = { Text(shown, style = MaterialTheme.typography.titleMedium) },
        )
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onChanged(current) },
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$title: $description"; stateDescription = shown }.testTag(tag),
        )
    }
}
