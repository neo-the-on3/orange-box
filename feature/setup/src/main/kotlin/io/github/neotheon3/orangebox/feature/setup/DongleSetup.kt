package io.github.neotheon3.orangebox.feature.setup

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.bluetooth.BluetoothAvailability
import io.github.neotheon3.orangebox.core.bluetooth.DongleCandidate
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.bluetooth.DongleTestResult
import io.github.neotheon3.orangebox.core.bluetooth.DongleTester
import io.github.neotheon3.orangebox.core.bluetooth.ObdDongleProvider
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.obd.ObdError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Passed(val adapter: String, val protocol: String, val alreadyConnected: Boolean) : TestState
    data class Failed(val kind: FailureKind, val message: String) : TestState
}

enum class FailureKind { NOT_PAIRED, PERMISSION, UNSUPPORTED, OTHER }

data class DongleSetupUiState(
    val candidates: List<DongleCandidate> = emptyList(),
    /** BLUETOOTH_CONNECT is missing (API 31+), so bonded devices can't be listed. */
    val needsPermission: Boolean = false,
    val selectedAddress: String? = null,
    val test: TestState = TestState.Idle,
    val autoStart: Boolean = false,
    val bluetooth: BluetoothAvailability = BluetoothAvailability.ON,
)

@HiltViewModel
class DongleSetupViewModel @Inject constructor(
    private val provider: ObdDongleProvider,
    private val tester: DongleTester,
    private val prefs: AppPreferencesRepository,
) : ViewModel() {
    private val list = MutableStateFlow(emptyList<DongleCandidate>() to false)
    private val test = MutableStateFlow<TestState>(TestState.Idle)
    private val bluetooth = MutableStateFlow(BluetoothAvailability.ON)

    val state: StateFlow<DongleSetupUiState> = combine(list, provider.selected, test, prefs.preferences, bluetooth) { (c, perm), sel, t, p, bt ->
        DongleSetupUiState(c, perm, sel?.address, t, p.autoStartOnDongleConnect, bt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DongleSetupUiState())

    init { refresh() }

    fun refresh() {
        bluetooth.value = provider.bluetoothAvailability()
        list.value = try {
            provider.candidates() to false
        } catch (e: ObdError.PermissionDenied) {
            emptyList<DongleCandidate>() to true
        }
    }

    fun select(candidate: DongleCandidate) {
        test.value = TestState.Idle
        viewModelScope.launch { provider.select(candidate) }
    }

    fun testConnection() {
        if (test.value == TestState.Running) return
        test.value = TestState.Running
        viewModelScope.launch {
            test.value = when (val r = tester.testSelected()) {
                is DongleTestResult.Success -> TestState.Passed(r.info.adapterId, r.info.protocol.label, r.alreadyConnected)
                is DongleTestResult.Failure -> TestState.Failed(
                    when (r.error) {
                        is ObdError.NotPaired -> FailureKind.NOT_PAIRED
                        is ObdError.PermissionDenied -> FailureKind.PERMISSION
                        is ObdError.UnsupportedProtocol -> FailureKind.UNSUPPORTED
                        else -> FailureKind.OTHER
                    },
                    r.error.message.orEmpty(),
                )
            }
        }
    }

    fun setAutoStart(enabled: Boolean) { viewModelScope.launch { prefs.setAutoStartOnDongleConnect(enabled) } }
}

@Composable
fun DongleSetupContent(
    state: DongleSetupUiState,
    onSelect: (DongleCandidate) -> Unit,
    onTest: () -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    permissionSlot: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.bluetooth) {
            BluetoothAvailability.UNAVAILABLE -> Text(
                stringResource(R.string.dongle_bt_unavailable), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("dongle_bt_unavailable"),
            )
            BluetoothAvailability.OFF -> Text(
                stringResource(R.string.dongle_bt_off), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("dongle_bt_off"),
            )
            BluetoothAvailability.ON -> Unit
        }
        if (state.needsPermission) {
            Text(stringResource(R.string.dongle_needs_permission), modifier = Modifier.testTag("dongle_needs_permission"))
            permissionSlot()
        } else if (state.bluetooth == BluetoothAvailability.ON && state.candidates.none { !it.simulated }) {
            Text(stringResource(R.string.dongle_none_paired), modifier = Modifier.testTag("dongle_empty"))
        }
        Column(Modifier.selectableGroup()) {
            state.candidates.forEach { c ->
                val selected = c.address.equals(state.selectedAddress, ignoreCase = true)
                ListItem(
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text(if (c.simulated) stringResource(R.string.dongle_simulated) else c.address) },
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    modifier = Modifier
                        .selectable(selected = selected, onClick = { onSelect(c) }, role = Role.RadioButton)
                        .testTag("dongle_${if (c.simulated) "simulated" else c.address}"),
                )
            }
        }
        if (state.bluetooth != BluetoothAvailability.UNAVAILABLE) {
            OutlinedButton(onClick = onOpenBluetoothSettings, modifier = Modifier.testTag("dongle_bt_settings")) {
                Text(stringResource(if (state.bluetooth == BluetoothAvailability.OFF) R.string.dongle_turn_on_bt else R.string.dongle_open_bt_settings))
            }
        }
        Button(
            onClick = onTest,
            enabled = state.selectedAddress != null && state.test != TestState.Running,
            modifier = Modifier.testTag("dongle_test"),
        ) { Text(stringResource(R.string.dongle_test)) }
        TestResultCard(state.test, onOpenBluetoothSettings)
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = state.autoStart, onValueChange = onAutoStartChange, role = Role.Switch)
                .padding(vertical = 8.dp)
                .testTag("dongle_auto_start"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.auto_start_title))
                Text(stringResource(R.string.auto_start_summary), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = state.autoStart, onCheckedChange = null)
        }
    }
}

@Composable
private fun TestResultCard(test: TestState, onOpenBluetoothSettings: () -> Unit) {
    if (test == TestState.Idle) return
    Card(Modifier.fillMaxWidth().testTag("dongle_test_result").semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (test) {
                TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text(stringResource(R.string.dongle_testing))
                }
                is TestState.Passed -> Text(
                    if (test.alreadyConnected) stringResource(R.string.dongle_test_already, test.protocol)
                    else stringResource(R.string.dongle_test_ok, test.adapter, test.protocol),
                    color = MaterialTheme.colorScheme.primary,
                )
                is TestState.Failed -> {
                    Text(stringResource(R.string.dongle_test_failed, test.message), color = MaterialTheme.colorScheme.error)
                    when (test.kind) {
                        FailureKind.NOT_PAIRED -> {
                            Text(stringResource(R.string.dongle_repair_help), modifier = Modifier.testTag("dongle_repair_help"))
                            OutlinedButton(onClick = onOpenBluetoothSettings) { Text(stringResource(R.string.dongle_open_bt_settings)) }
                        }
                        FailureKind.UNSUPPORTED -> Text(stringResource(R.string.dongle_unsupported_help))
                        FailureKind.PERMISSION -> Text(stringResource(R.string.dongle_needs_permission))
                        FailureKind.OTHER -> Text(stringResource(R.string.dongle_other_help))
                    }
                }
                TestState.Idle -> Unit
            }
        }
    }
}

/**
 * Opens Bluetooth settings, falling back to the main Settings screen where there is no Bluetooth page
 * (#121). Returns false, after logging, when neither can be opened.
 */
internal fun openBluetoothSettings(context: Context): Boolean {
    for (action in listOf(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS)) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (action != Settings.ACTION_BLUETOOTH_SETTINGS) OrangeLog.w(OrangeLog.Areas.BLUETOOTH, "bt_settings_fallback", "action" to action)
            return true
        } catch (e: ActivityNotFoundException) {
            continue
        } catch (e: SecurityException) {
            continue
        }
    }
    OrangeLog.w(OrangeLog.Areas.BLUETOOTH, "bt_settings_unavailable")
    return false
}

@Composable
internal fun rememberOpenBluetoothSettings(): () -> Unit {
    val context = LocalContext.current
    val failed = stringResource(R.string.dongle_settings_unavailable)
    return {
        if (!openBluetoothSettings(context)) Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
    }
}
