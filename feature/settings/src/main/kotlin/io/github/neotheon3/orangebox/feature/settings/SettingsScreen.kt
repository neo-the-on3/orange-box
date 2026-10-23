package io.github.neotheon3.orangebox.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.model.BaseUrlValidator

@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onModeSelected = viewModel::onModeSelected,
        onUrlChanged = viewModel::onUrlChanged,
        onSaveUrl = viewModel::onSaveUrl,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onModeSelected: (BackendMode) -> Unit,
    onUrlChanged: (String) -> Unit,
    onSaveUrl: () -> Unit,
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
