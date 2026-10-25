package io.github.neotheon3.orangebox.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.model.BaseUrlValidator

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenVehicle: () -> Unit = {},
    onOpenDongle: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val apps by viewModel.launchableApps.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    RefreshOnResume(viewModel::refreshFullScreen)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pickContact = androidx.activity.compose.rememberLauncherForActivityResult(PickPhoneNumber()) { uri ->
        if (uri != null) {
            scope.launch {
                val picked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PickedContactReader.read(context, uri) }
                viewModel.onContactPicked(picked ?: PickedContact(null, ""))
            }
        }
    }
    SettingsScreen(
        state = state,
        onBack = onBack,
        onModeSelected = viewModel::onModeSelected,
        onUrlChanged = viewModel::onUrlChanged,
        onSaveUrl = viewModel::onSaveUrl,
        onOpenVehicle = onOpenVehicle,
        onOpenDongle = onOpenDongle,
        onOpenPermissions = onOpenPermissions,
        onAutoStartChanged = viewModel::onAutoStartChanged,
        launchableApps = apps,
        dashboardActions = DashboardSettingsActions(
            onButtonChanged = viewModel::onDashboardButtonChanged,
            onEmergencyNumberChanged = viewModel::onEmergencyNumberChanged,
            onPickApp = viewModel::loadApps,
            onAppChosen = viewModel::onQuickLaunchAppChosen,
            onOverspeedChanged = viewModel::onOverspeedChanged,
            onAutoShowChanged = viewModel::onAutoShowDashboardChanged,
            onOpenFullScreenSettings = {
                runCatching { context.startActivity(io.github.neotheon3.orangebox.core.driving.FullScreenIntents.settingsIntent(context)) }
            },
        ),
        actions = SettingsActions(
            onUnitsChanged = viewModel::onUnitsChanged,
            onAlertsChanged = viewModel::onAlertsChanged,
            onDiagnosticsPromptChanged = viewModel::onDiagnosticsPromptChanged,
            crash = CrashSettingsActions(
                onChanged = viewModel::onCrashChanged,
                onPickContact = {
                    // No contacts app to pick from: type the number instead.
                    runCatching { pickContact.launch(Unit) }.onFailure { viewModel.onEditContact() }
                },
                onEditContact = viewModel::onEditContact,
                onContactDraftChanged = viewModel::onContactDraftChanged,
                onSaveContact = viewModel::onSaveContact,
                onCancelContact = viewModel::onCancelContact,
                onClearContact = viewModel::onClearContact,
            ),
        ),
    )
}

/** Callbacks of the sections M7/M8 added (units, alerts, crash, diagnostics). */
data class SettingsActions(
    val onUnitsChanged: (io.github.neotheon3.orangebox.core.model.UnitPreferences) -> Unit = {},
    val onAlertsChanged: (io.github.neotheon3.orangebox.core.model.AlertPreferences) -> Unit = {},
    val crash: CrashSettingsActions = CrashSettingsActions(),
    val onDiagnosticsPromptChanged: (Boolean) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onModeSelected: (BackendMode) -> Unit,
    onUrlChanged: (String) -> Unit,
    onSaveUrl: () -> Unit,
    onOpenVehicle: () -> Unit = {},
    onOpenDongle: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onAutoStartChanged: (Boolean) -> Unit = {},
    launchableApps: List<io.github.neotheon3.orangebox.core.driving.LaunchableApp>? = null,
    dashboardActions: DashboardSettingsActions = DashboardSettingsActions(),
    actions: SettingsActions = SettingsActions(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.settings_backend_section), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_backend_help), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.selectableGroup()) {
                BackendMode.entries.forEach { mode ->
                    val label = stringResource(
                        when (mode) {
                            BackendMode.OFF -> R.string.settings_mode_off
                            BackendMode.FAKE -> R.string.settings_mode_fake
                            BackendMode.REMOTE -> R.string.settings_mode_remote
                        },
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.config.mode == mode,
                                onClick = { onModeSelected(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp)
                            .testTag("settings_mode_${mode.wireName}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.config.mode == mode, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            if (state.config.mode == BackendMode.REMOTE) {
                OutlinedTextField(
                    value = state.urlDraft,
                    onValueChange = onUrlChanged,
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    placeholder = { Text(stringResource(R.string.settings_base_url_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = state.urlResult == BaseUrlValidator.Result.NotHttps ||
                        state.urlResult == BaseUrlValidator.Result.Malformed,
                    supportingText = state.urlResult?.let { result ->
                        {
                            Text(
                                stringResource(
                                    when (result) {
                                        BaseUrlValidator.Result.Valid -> R.string.settings_url_saved
                                        BaseUrlValidator.Result.Empty -> R.string.settings_url_cleared
                                        BaseUrlValidator.Result.NotHttps -> R.string.settings_url_not_https
                                        BaseUrlValidator.Result.Malformed -> R.string.settings_url_malformed
                                    },
                                ),
                                modifier = Modifier.testTag("settings_url_result"),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("settings_base_url"),
                )
                Button(onClick = onSaveUrl, modifier = Modifier.testTag("settings_save_url")) {
                    Text(stringResource(R.string.settings_save_url))
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.settings_setup_section), style = MaterialTheme.typography.titleMedium)
            SetupRow(
                stringResource(R.string.settings_vehicle), state.vehicleName ?: stringResource(R.string.settings_not_set),
                onOpenVehicle, "settings_vehicle",
            )
            SetupRow(
                stringResource(R.string.settings_dongle), state.dongleName ?: stringResource(R.string.settings_not_set),
                onOpenDongle, "settings_dongle",
            )
            SetupRow(stringResource(R.string.settings_permissions), stringResource(permissionsSummaryRes()), onOpenPermissions, "settings_permissions")
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_auto_start)) },
                supportingContent = { Text(stringResource(R.string.settings_auto_start_summary)) },
                trailingContent = { Switch(checked = state.autoStart, onCheckedChange = null) },
                modifier = Modifier
                    .toggleable(value = state.autoStart, onValueChange = onAutoStartChanged, role = Role.Switch)
                    .testTag("settings_auto_start"),
            )
            SwitchRow(
                R.string.settings_diagnostics_prompt, R.string.settings_diagnostics_prompt_summary, state.diagnosticsPrompt,
                "settings_diagnostics_prompt", actions.onDiagnosticsPromptChanged,
            )
            HorizontalDivider()
            DashboardSettingsSection(state.dashboard, launchableApps, dashboardActions, state.units)
            HorizontalDivider()
            UnitsSettingsSection(state.units, actions.onUnitsChanged)
            HorizontalDivider()
            AlertsSettingsSection(state.alerts, actions.onAlertsChanged)
            HorizontalDivider()
            CrashSettingsSection(state.crash, actions.crash)
            HorizontalDivider()
            Text(stringResource(R.string.settings_about_section), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_about_version, state.version.versionName, state.version.versionCode),
                modifier = Modifier.testTag("settings_about_version"),
            )
            Text(
                stringResource(R.string.settings_about_build, state.version.gitSha, state.version.ciRun ?: stringResource(R.string.settings_about_local)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings_about_build"),
            )
        }
    }
}

@Composable
private fun SetupRow(title: String, summary: String, onClick: () -> Unit, tag: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        modifier = Modifier.clickable(onClick = onClick, role = Role.Button).testTag(tag),
    )
}

/**
 * Names only the runtime permissions this Android version asks for: Bluetooth (Nearby devices) from
 * API 31, notifications from 33, location everywhere. Mirrors feature:setup's AppPermission.forSdk.
 */
internal fun permissionsSummaryRes(sdk: Int = android.os.Build.VERSION.SDK_INT): Int = when {
    sdk >= android.os.Build.VERSION_CODES.TIRAMISU -> R.string.settings_permissions_summary
    sdk >= android.os.Build.VERSION_CODES.S -> R.string.settings_permissions_summary_bt_location
    else -> R.string.settings_permissions_summary_location
}
