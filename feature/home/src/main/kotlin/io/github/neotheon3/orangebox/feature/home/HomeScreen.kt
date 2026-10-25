package io.github.neotheon3.orangebox.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.designsystem.StatusPill
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode

/** An extra overflow-menu entry (e.g. the debug-only Simulator). */
data class HomeMenuEntry(val label: String, val testTag: String, val onClick: () -> Unit)

@Composable
fun HomeRoute(
    onOpenSettings: () -> Unit,
    onOpenDashboard: () -> Unit,
    extraMenuEntries: List<HomeMenuEntry> = emptyList(),
    onOpenTrips: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onOpenSettings = onOpenSettings,
        onOpenDashboard = onOpenDashboard,
        extraMenuEntries = extraMenuEntries,
        onOpenTrips = onOpenTrips,
        onOpenDiagnostics = onOpenDiagnostics,
        onObdConnect = viewModel::onConnect,
        onObdRetry = viewModel::onRetry,
        onObdDisconnect = viewModel::onDisconnect,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenSettings: () -> Unit,
    onOpenDashboard: () -> Unit = {},
    extraMenuEntries: List<HomeMenuEntry> = emptyList(),
    onObdConnect: () -> Unit = {},
    onObdRetry: () -> Unit = {},
    onObdDisconnect: () -> Unit = {},
    onOpenTrips: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenTrips, modifier = Modifier.testTag("home_trips")) {
                        Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.home_open_trips))
                    }
                    IconButton(onClick = onOpenDiagnostics, modifier = Modifier.testTag("home_diagnostics")) {
                        Icon(
                            io.github.neotheon3.orangebox.core.dashboardui.DashboardIcons.MilEngine,
                            contentDescription = stringResource(R.string.home_open_diagnostics),
                        )
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("home_settings")) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_open_settings))
                    }
                    if (extraMenuEntries.isNotEmpty()) {
                        var expanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("home_more")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_more))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            extraMenuEntries.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.label) },
                                    onClick = { expanded = false; entry.onClick() },
                                    modifier = Modifier.testTag(entry.testTag),
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .testTag("home_scroll")
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DriveButton(state.obd, onConnect = onObdConnect, onOpenDashboard = onOpenDashboard)
            // The backend is an advanced, optional feature: only say so when it is on (#192).
            if (state.showBackendPill) {
                StatusPill(
                    text = stringResource(R.string.home_backend_mode, backendModeLabel(state.backend.mode)),
                    modifier = Modifier.testTag("home_backend_mode"),
                )
            }
            if (state.showRemoteWarning) {
                Text(
                    stringResource(R.string.home_remote_not_configured),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("home_remote_warning"),
                )
            }
            ObdStatusCard(state.obd, onObdConnect, onObdRetry, onObdDisconnect)
            if (state.obd is io.github.neotheon3.orangebox.core.obd.ObdConnectionState.Connected) {
                MiniDashboardCard(state.dashboard, state.live, onOpenDashboard)
            }
        }
    }
}

@Composable
fun backendModeLabel(mode: BackendMode): String = stringResource(
    when (mode) {
        BackendMode.OFF -> R.string.backend_mode_off
        BackendMode.FAKE -> R.string.backend_mode_fake
        BackendMode.REMOTE -> R.string.backend_mode_remote
    },
)

@Preview
@Composable
private fun HomePreview() {
    OrangeBoxTheme { HomeScreen(HomeUiState(BackendConfig(), loading = false), onOpenSettings = {}) }
}
