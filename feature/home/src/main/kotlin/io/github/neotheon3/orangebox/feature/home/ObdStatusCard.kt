package io.github.neotheon3.orangebox.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdError

/** OBD connection status: state, retry n/5, protocol, voltage, last error; Retry when Failed. */
@Composable
fun ObdStatusCard(
    state: ObdConnectionState,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().testTag("home_obd_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_obd_title), style = MaterialTheme.typography.titleMedium)
            val status = Modifier.testTag("home_obd_status")
            when (state) {
                ObdConnectionState.Idle -> {
                    Text(stringResource(R.string.obd_idle), modifier = status)
                    Button(onClick = onConnect, modifier = Modifier.testTag("home_obd_connect")) {
                        Text(stringResource(R.string.obd_connect))
                    }
                }
                is ObdConnectionState.Connecting -> {
                    Text(
                        if (state.retry == 0) stringResource(R.string.obd_connecting)
                        else stringResource(R.string.obd_connecting_retry, state.retry, state.maxRetries),
                        modifier = status,
                    )
                    CancelButton(onDisconnect)
                }
                is ObdConnectionState.Initializing -> {
                    Text(stringResource(R.string.obd_initializing), modifier = status)
                    CancelButton(onDisconnect)
                }
                is ObdConnectionState.WaitingToRetry -> {
                    Text(
                        stringResource(R.string.obd_waiting, state.nextRetry, state.maxRetries, state.secondsRemaining),
                        modifier = status,
                    )
                    ErrorText(state.lastError)
                    CancelButton(onDisconnect)
                }
                is ObdConnectionState.Connected -> {
                    Text(stringResource(R.string.obd_connected), modifier = status)
                    Text(stringResource(R.string.obd_protocol, state.info.protocol.label), modifier = Modifier.testTag("home_obd_protocol"))
                    Text(
                        state.info.voltage?.let { stringResource(R.string.obd_voltage, it) } ?: stringResource(R.string.obd_voltage_unknown),
                        modifier = Modifier.testTag("home_obd_voltage"),
                    )
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.testTag("home_obd_disconnect")) {
                        Text(stringResource(R.string.obd_disconnect))
                    }
                }
                is ObdConnectionState.Failed -> {
                    Text(pluralStringResource(R.plurals.obd_failed, state.attempts, state.attempts), modifier = status)
                    ErrorText(state.reason)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry, modifier = Modifier.testTag("home_obd_retry")) {
                            Text(stringResource(R.string.obd_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CancelButton(onDisconnect: () -> Unit) {
    OutlinedButton(onClick = onDisconnect, modifier = Modifier.testTag("home_obd_cancel")) {
        Text(stringResource(R.string.obd_cancel))
    }
}

@Composable
private fun ErrorText(error: ObdError) {
    Text(
        stringResource(R.string.obd_last_error, obdErrorLabel(error)),
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag("home_obd_error"),
    )
}

@Composable
fun obdErrorLabel(error: ObdError): String = when (error) {
    is ObdError.NotPaired -> stringResource(R.string.obd_error_not_paired)
    is ObdError.UnsupportedProtocol -> stringResource(R.string.obd_error_unsupported)
    is ObdError.PermissionDenied -> stringResource(R.string.obd_error_permission)
    is ObdError.Timeout -> stringResource(R.string.obd_error_timeout)
    is ObdError.Io -> error.message ?: stringResource(R.string.obd_error_io)
}
