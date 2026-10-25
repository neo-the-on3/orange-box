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
import androidx.compose.material3.FilterChip
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
import io.github.neotheon3.orangebox.core.obd.VinDecoder
import io.github.neotheon3.orangebox.core.simulator.DriveScript
import io.github.neotheon3.orangebox.core.simulator.DtcList
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
            DtcInjector(actions)
            Text(
                if (sim.dtcs.isEmpty()) "Stored DTCs: none" else "Stored DTCs: " + sim.dtcs.joinToString(", "),
                modifier = Modifier.testTag("sim_dtc_list"),
            )
            Text(
                "Pending: " + sim.pendingDtcs.ifEmpty { listOf("none") }.joinToString(", ") +
                    "   Permanent: " + sim.permanentDtcs.ifEmpty { listOf("none") }.joinToString(", "),
                modifier = Modifier.testTag("sim_dtc_other_lists"),
            )
            ToggleRow("MIL (check engine) on", sim.milOn, "sim_mil") { v -> actions.update { it.copy(milOn = v) } }
            OutlinedButton(onClick = actions::clearDtcs, modifier = Modifier.testTag("sim_dtc_clear")) { Text("Clear DTCs (mode 04)") }
            VinEditor(sim.vin, actions)

            Section("App readout (debug)")
            AppReadout(state, actions)

            Section("Failures")
            ToggleRow("Connection failure", sim.connectionFailure, "sim_fail_connection") { v -> actions.update { it.copy(connectionFailure = v) } }
            ToggleRow("Unsupported protocol", sim.unsupportedProtocol, "sim_fail_protocol") { v -> actions.update { it.copy(unsupportedProtocol = v) } }
            ToggleRow("Slow responses (1.5 s)", sim.slowResponses, "sim_slow") { v -> actions.update { it.copy(slowResponses = v) } }
            ToggleRow(
                "Delay connect (${SimulatorState.CONNECT_DELAY_SECONDS} s)", sim.connectDelaySeconds > 0, "sim_delay_connect",
            ) { v -> actions.update { it.copy(connectDelaySeconds = if (v) SimulatorState.CONNECT_DELAY_SECONDS else 0) } }
            ToggleRow("Garbled responses (~30%)", sim.garbledResponses, "sim_garbled") { v -> actions.update { it.copy(garbledResponses = v) } }
            ToggleRow("Garbled protocol reply (ATDPN, always)", sim.garbledProtocolReplies, "sim_garbled_atdpn") { v ->
                actions.update { it.copy(garbledProtocolReplies = v) }
            }

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
private fun DtcInjector(actions: SimulatorActions) {
    var dtc by remember { mutableStateOf("P0133") }
    var dtcError by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf(DtcList.STORED) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DtcList.entries.forEach { l ->
            FilterChip(
                selected = list == l,
                onClick = { list = l },
                label = { Text(l.name.lowercase().replaceFirstChar { it.uppercase() }) },
                modifier = Modifier.testTag("sim_dtc_kind_${l.name.lowercase()}"),
            )
        }
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = dtc,
            onValueChange = { dtc = it; dtcError = false },
            label = { Text("DTC") },
            isError = dtcError,
            supportingText = {
                if (dtcError) {
                    Text("Invalid code. Use P, C, B or U and 4 hex digits, e.g. P0133.", modifier = Modifier.testTag("sim_dtc_error"))
                }
            },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("sim_dtc_input"),
        )
        Button(onClick = { dtcError = !actions.injectDtc(dtc, list) }, modifier = Modifier.padding(top = 8.dp).testTag("sim_dtc_inject")) { Text("Inject") }
    }
    // The M6 acceptance set: Ignition, Air/Fuel and Network lamps go red on the Diagnostics screen.
    OutlinedButton(
        onClick = { DEMO_DTCS.forEach { actions.injectDtc(it, list) } },
        modifier = Modifier.testTag("sim_dtc_demo"),
    ) { Text("Inject " + DEMO_DTCS.joinToString(" + ")) }
}

/** Codes injected by "Inject P0300 + P0420 + U0100". */
internal val DEMO_DTCS = listOf("P0300", "P0420", "U0100")

@Composable
private fun VinEditor(current: String, actions: SimulatorActions) {
    var vin by remember(current) { mutableStateOf(current) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = vin,
            onValueChange = { vin = it.take(17) },
            label = { Text("VIN reported by the car") },
            supportingText = { Text("${vin.length}/17. Not validated, so invalid VINs can be tested.") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("sim_vin_input"),
        )
        Button(onClick = { actions.setVin(vin) }, modifier = Modifier.padding(top = 8.dp).testTag("sim_vin_set")) { Text("Set") }
    }
}

/** Cumulative eco-driving time this trip, compared with the docs/telemetry.md goldens (City 91 s, Harsh 19 s). */
internal fun ecoReadout(ecoS: Double): String = "Eco driving (trip): ${"%.1f".format(java.util.Locale.US, ecoS)} s"

@Composable
private fun AppReadout(state: SimulatorUiState, actions: SimulatorActions) {
    Card(Modifier.fillMaxWidth().testTag("sim_readout")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val snap = state.telemetry
            if (snap == null) {
                Text("Telemetry: not connected", modifier = Modifier.testTag("sim_readout_telemetry"))
            } else {
                val i = snap.instant
                val t = snap.trip
                fun flag(on: Boolean) = if (on) "ON" else "off"
                Text("Harsh accel: ${flag(i.harshAccel)} · count ${t.harshAccelCount}", modifier = Modifier.testTag("sim_readout_harsh_accel"))
                Text("Harsh brake: ${flag(i.harshBrake)} · count ${t.harshBrakeCount}", modifier = Modifier.testTag("sim_readout_harsh_brake"))
                Text(
                    "Overspeed: ${flag(i.overspeed)} · ${"%.1f".format(java.util.Locale.US, t.overspeedS)} s · events ${t.overspeedCount}",
                    modifier = Modifier.testTag("sim_readout_overspeed"),
                )
                Text(ecoReadout(t.ecoS), modifier = Modifier.testTag("sim_readout_eco"))
                Text(
                    "Idle ${"%.1f".format(java.util.Locale.US, t.idleS)} s · " +
                        "fuel cut ${"%.1f".format(java.util.Locale.US, t.fuelCutS)} s",
                    modifier = Modifier.testTag("sim_readout_times"),
                )
                Text(
                    "Accel: " + (i.accelMps2?.let { "%.2f m/s²".format(java.util.Locale.US, it) } ?: "–") +
                        " · fuel cut ${flag(i.fuelCut)} · idle ${flag(i.idling)} · eco ${flag(i.eco)}",
                    modifier = Modifier.testTag("sim_readout_flags"),
                )
            }
            OutlinedButton(onClick = actions::readDiagnostics, modifier = Modifier.testTag("sim_readout_read")) {
                Text("Read DTCs + VIN from car")
            }
            state.readout?.let { r ->
                if (r.error != null) {
                    Text("Read failed: ${r.error}", color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sim_readout_error"))
                } else {
                    fun codes(l: List<String>?) = l?.ifEmpty { listOf("none") }?.joinToString(", ") ?: "unreadable"
                    Text("Stored (03): ${codes(r.stored)}", modifier = Modifier.testTag("sim_readout_stored"))
                    Text("Pending (07): ${codes(r.pending)}", modifier = Modifier.testTag("sim_readout_pending"))
                    Text("Permanent (0A): ${codes(r.permanent)}", modifier = Modifier.testTag("sim_readout_permanent"))
                    Text(
                        "VIN: " + when (val v = r.vin) {
                            is VinDecoder.Result.Valid -> v.vin + if (v.checkDigitValid) " (check digit OK)" else " (check digit mismatch)"
                            is VinDecoder.Result.Invalid -> "invalid: ${v.reason}"
                            null -> "–"
                        },
                        modifier = Modifier.testTag("sim_readout_vin"),
                    )
                }
            }
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
    ObdConnectionState.LinkLost -> "Link lost (waiting for dongle)"
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
