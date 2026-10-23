package io.github.neotheon3.orangebox.simulator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvent
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.simulator.DriveScript
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import kotlin.math.roundToInt

@Composable
fun SimulatorRoute(onBack: () -> Unit, viewModel: SimulatorViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SimulatorScreen(state, viewModel, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulatorScreen(state: SimulatorUiState, actions: SimulatorActions, onBack: () -> Unit) {
    val sim = state.sim
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Simulator") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("sim_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sim.activeFailures.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().testTag("sim_active_failures"),
                ) {
                    Text(
                        "Active failures: " + sim.activeFailures.joinToString(", "),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            StatusPanel(state)

            Section("Dongle")
            ToggleRow("Use simulated dongle", state.simulatedSelected, "sim_use_simulated") {
                actions.setUseSimulatedDongle(it)
            }
            ToggleRow("Paired", sim.paired, "sim_paired") { v -> actions.update { it.copy(paired = v) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions::linkConnected, modifier = Modifier.testTag("sim_link_connected")) { Text("BT connected") }
                OutlinedButton(onClick = actions::linkDisconnected, modifier = Modifier.testTag("sim_link_disconnected")) { Text("BT disconnected") }
            }

            Section("Drive scripts")
            Text(
                sim.runningScript?.let { id -> "Playing: " + (DriveScript.byId(id)?.label ?: id) } ?: "No script running",
                modifier = Modifier.testTag("sim_script_status"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DriveScript.ALL.forEach { script ->
                    OutlinedButton(onClick = { actions.startScript(script.id) }, modifier = Modifier.testTag("sim_script_${script.id}")) {
                        Text(script.label)
                    }
                }
                Button(onClick = actions::stopScript, enabled = sim.runningScript != null, modifier = Modifier.testTag("sim_script_stop")) {
                    Text("Stop script")
                }
            }

            Section("Vehicle")
            ToggleRow("Engine on", sim.engineOn, "sim_engine") { v -> actions.update { it.copy(engineOn = v) } }
            SliderRow("Speed", sim.speedKph, "km/h", SimulatorState.SPEED_RANGE, "sim_speed") { v -> actions.update { it.copy(speedKph = v) } }
            SliderRow("RPM", sim.rpm, "rpm", SimulatorState.RPM_RANGE, "sim_rpm") { v -> actions.update { it.copy(rpm = v) } }
            SliderRow("Coolant", sim.coolantC, "°C", SimulatorState.COOLANT_RANGE, "sim_coolant") { v -> actions.update { it.copy(coolantC = v) } }
            SliderRow("Throttle", sim.throttlePercent, "%", SimulatorState.THROTTLE_RANGE, "sim_throttle") { v -> actions.update { it.copy(throttlePercent = v) } }
            SliderRow("Fuel", sim.fuelPercent, "%", SimulatorState.FUEL_RANGE, "sim_fuel") { v -> actions.update { it.copy(fuelPercent = v) } }

            Section("Diagnostics")
            var dtc by remember { mutableStateOf("P0133") }
            var dtcError by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = dtc,
                    onValueChange = { dtc = it; dtcError = false },
                    label = { Text("DTC") },
                    isError = dtcError,
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("sim_dtc_input"),
                )
                Button(onClick = { dtcError = !actions.injectDtc(dtc) }, modifier = Modifier.testTag("sim_dtc_inject")) { Text("Inject") }
            }
            Text(
                if (sim.dtcs.isEmpty()) "Stored DTCs: none" else "Stored DTCs: " + sim.dtcs.joinToString(", "),
                modifier = Modifier.testTag("sim_dtc_list"),
            )
            ToggleRow("MIL (check engine) on", sim.milOn, "sim_mil") { v -> actions.update { it.copy(milOn = v) } }
            OutlinedButton(onClick = actions::clearDtcs, modifier = Modifier.testTag("sim_dtc_clear")) { Text("Clear DTCs") }

            Section("Failures")
            ToggleRow("Connection failure", sim.connectionFailure, "sim_fail_connection") { v -> actions.update { it.copy(connectionFailure = v) } }
            ToggleRow("Unsupported protocol", sim.unsupportedProtocol, "sim_fail_protocol") { v -> actions.update { it.copy(unsupportedProtocol = v) } }
            ToggleRow("Slow responses (1.5 s)", sim.slowResponses, "sim_slow") { v -> actions.update { it.copy(slowResponses = v) } }
            ToggleRow("Garbled responses (~30%)", sim.garbledResponses, "sim_garbled") { v -> actions.update { it.copy(garbledResponses = v) } }

            Section("Backend mode")
            BackendMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(state.backendMode == mode, role = Role.RadioButton) { actions.setBackendMode(mode) }
                        .testTag("sim_backend_${mode.wireName}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.backendMode == mode, onClick = null)
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, Modifier.padding(start = 12.dp))
                }
            }

            HorizontalDivider()
            Button(onClick = actions::reset, modifier = Modifier.testTag("sim_reset")) { Text("Reset simulator") }
        }
    }
}

@Composable
private fun StatusPanel(state: SimulatorUiState) {
    Card(Modifier.fillMaxWidth().testTag("sim_status")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val c = state.connection
            Text("State: ${stateLabel(c)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("sim_status_state"))
            val attempt = when (c) {
                is ObdConnectionState.Connecting -> "${c.retry}/${c.maxRetries}"
                is ObdConnectionState.Initializing -> "${c.retry}/${c.maxRetries}"
                is ObdConnectionState.WaitingToRetry -> "${c.nextRetry}/${c.maxRetries}"
                is ObdConnectionState.Failed -> "gave up after ${c.attempts}"
                else -> "–"
            }
            Text("Retry: $attempt", modifier = Modifier.testTag("sim_status_attempt"))
            Text(
                "Next retry: " + ((c as? ObdConnectionState.WaitingToRetry)?.let { "${it.secondsRemaining} s" } ?: "–"),
                modifier = Modifier.testTag("sim_status_countdown"),
            )
            val err = (c as? ObdConnectionState.WaitingToRetry)?.lastError ?: (c as? ObdConnectionState.Failed)?.reason
            Text("Last error: " + (err?.let { "${it::class.simpleName}: ${it.message}" } ?: "–"), modifier = Modifier.testTag("sim_status_error"))
            Text(
                "Last link event: " + when (val e = state.lastLinkEvent) {
                    is DongleLinkEvent.Connected -> "connected (${e.address})"
                    is DongleLinkEvent.Disconnected -> "disconnected (${e.address})"
                    null -> "–"
                },
                modifier = Modifier.testTag("sim_status_link"),
            )
        }
    }
}

private fun stateLabel(c: ObdConnectionState) = when (c) {
    ObdConnectionState.Idle -> "Idle"
    is ObdConnectionState.Connecting -> "Connecting"
    is ObdConnectionState.Initializing -> "Initializing"
    is ObdConnectionState.WaitingToRetry -> "Waiting to retry"
    is ObdConnectionState.Connected -> "Connected (${c.info.protocol.label}, ${c.info.voltage ?: "?"} V)"
    is ObdConnectionState.Failed -> "Failed"
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).testTag(tag).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SliderRow(label: String, value: Int, unit: String, range: IntRange, tag: String, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value $unit")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.testTag(tag),
        )
    }
}
