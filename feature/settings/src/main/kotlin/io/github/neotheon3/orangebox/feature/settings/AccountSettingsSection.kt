package io.github.neotheon3.orangebox.feature.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.datastore.OtherTrips
import io.github.neotheon3.orangebox.core.datastore.SignInError
import io.github.neotheon3.orangebox.core.datastore.SyncError
import io.github.neotheon3.orangebox.core.datastore.SyncStatus
import io.github.neotheon3.orangebox.core.model.BackendMode
import java.util.Date

/** Callbacks of [AccountSettingsSection]. */
data class AccountActions(
    val onEmailChanged: (String) -> Unit = {},
    val onSendCode: () -> Unit = {},
    val onCodeChanged: (String) -> Unit = {},
    val onVerify: () -> Unit = {},
    val onResendCode: () -> Unit = {},
    val onChangeEmail: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onSignOutRequested: () -> Unit = {},
    val onSignOutDismissed: () -> Unit = {},
    val onSignOutConfirmed: () -> Unit = {},
    val onOtherTripsChoose: () -> Unit = {},
    val onMerge: () -> Unit = {},
    val onReplace: () -> Unit = {},
    val onReplaceConfirmed: () -> Unit = {},
    val onReplaceCancelled: () -> Unit = {},
    val onOtherTripsDismissed: () -> Unit = {},
)

/**
 * "Account and sync" under Server (M9). Remote: e-mail + one-time code sign-in, then who is signed in, the last
 * sync, Sync now and Sign out. Fake: no account, just the sync. Off: nothing (the section isn't shown).
 */
@Composable
internal fun AccountSettingsSection(
    state: AccountUiState,
    mode: BackendMode,
    remoteReady: Boolean,
    actions: AccountActions,
    modifier: Modifier = Modifier,
) {
    if (mode == BackendMode.OFF) return
    Column(modifier.testTag("settings_account"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.account_section), style = MaterialTheme.typography.titleMedium)
        when {
            mode == BackendMode.REMOTE && !remoteReady -> Text(
                stringResource(R.string.account_needs_url),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("account_needs_url"),
            )
            mode == BackendMode.REMOTE && !state.account.signedIn -> SignInForm(state.form, actions)
            else -> SyncPanel(state, mode, actions)
        }
    }
    if (state.confirmSignOut) {
        AlertDialog(
            onDismissRequest = actions.onSignOutDismissed,
            title = { Text(stringResource(R.string.account_sign_out_title)) },
            text = { Text(stringResource(R.string.account_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = actions.onSignOutConfirmed, modifier = Modifier.testTag("account_sign_out_confirm")) {
                    Text(stringResource(R.string.account_sign_out))
                }
            },
            dismissButton = { TextButton(onClick = actions.onSignOutDismissed) { Text(stringResource(R.string.account_cancel)) } },
        )
    }
    when (val d = state.otherTripsDialog) {
        OtherTripsDialog.Choose -> OtherTripsChoiceDialog(state.account.otherTrips, state.account.email, actions)
        is OtherTripsDialog.ConfirmReplace -> ReplaceConfirmDialog(d.trips, state.account.email, actions)
        null -> Unit
    }
}

/** After sign-in (or from Settings): what to do with the phone's trips that aren't this account's. */
@Composable
internal fun OtherTripsChoiceDialog(count: Int, email: String?, actions: AccountActions) {
    val who = email ?: stringResource(R.string.account_this_account)
    AlertDialog(
        onDismissRequest = actions.onOtherTripsDismissed,
        icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) },
        title = { Text(stringResource(R.string.account_other_trips_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(pluralStringResource(R.plurals.account_other_trips_body, count, count, who), style = MaterialTheme.typography.bodyMedium)
                ChoiceOption(
                    title = stringResource(R.string.account_merge),
                    body = stringResource(R.string.account_merge_body, who),
                    onClick = actions.onMerge,
                    tag = "account_merge",
                    primary = true,
                )
                ChoiceOption(
                    title = stringResource(R.string.account_replace),
                    body = stringResource(R.string.account_replace_body),
                    onClick = actions.onReplace,
                    tag = "account_replace",
                    primary = false,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onOtherTripsDismissed, modifier = Modifier.testTag("account_other_trips_cancel")) {
                Text(stringResource(R.string.account_cancel))
            }
        },
        modifier = Modifier.testTag("account_other_trips_dialog"),
    )
}

@Composable
private fun ChoiceOption(title: String, body: String, onClick: () -> Unit, tag: String, primary: Boolean) {
    val colors = if (primary) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
    } else {
        CardDefaults.outlinedCardColors()
    }
    val shape = MaterialTheme.shapes.medium
    if (primary) {
        Card(onClick = onClick, colors = colors, shape = shape, modifier = Modifier.fillMaxWidth().testTag(tag)) { OptionText(title, body) }
    } else {
        OutlinedCard(onClick = onClick, colors = colors, shape = shape, modifier = Modifier.fillMaxWidth().testTag(tag)) { OptionText(title, body) }
    }
}

@Composable
private fun OptionText(title: String, body: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}

/** Second step of Replace: the data loss, with counts, before anything is deleted. */
@Composable
internal fun ReplaceConfirmDialog(trips: OtherTrips, email: String?, actions: AccountActions) {
    val who = email ?: stringResource(R.string.account_this_account)
    AlertDialog(
        onDismissRequest = actions.onReplaceCancelled,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(pluralStringResource(R.plurals.account_replace_confirm_title, trips.count, trips.count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.account_replace_confirm_body, trips.count, trips.count, who), style = MaterialTheme.typography.bodyMedium)
                if (trips.neverUploaded > 0) {
                    Text(
                        pluralStringResource(R.plurals.account_replace_never_uploaded, trips.neverUploaded, trips.neverUploaded),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("account_replace_never_uploaded"),
                    )
                }
                Text(stringResource(R.string.account_replace_undo), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(
                onClick = actions.onReplaceConfirmed,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("account_replace_confirm"),
            ) { Text(pluralStringResource(R.plurals.account_replace_confirm, trips.count, trips.count)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onReplaceCancelled, modifier = Modifier.testTag("account_replace_cancel")) {
                Text(stringResource(R.string.account_cancel))
            }
        },
        modifier = Modifier.testTag("account_replace_dialog"),
    )
}

@Composable
private fun SignInForm(form: SignInForm, actions: AccountActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("account_sign_in")) {
        when (form.step) {
            SignInStep.EMAIL -> {
                Text(stringResource(R.string.account_sign_in_help), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = form.email,
                    onValueChange = actions.onEmailChanged,
                    label = { Text(stringResource(R.string.account_email)) },
                    placeholder = { Text(stringResource(R.string.account_email_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                    singleLine = true,
                    enabled = !form.busy,
                    isError = form.error != null,
                    supportingText = form.error?.let { e -> { ErrorText(e, form.retryAfterSeconds) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSend = { actions.onSendCode() }),
                    modifier = Modifier.fillMaxWidth().testTag("account_email"),
                )
                Button(onClick = actions.onSendCode, enabled = !form.busy && form.email.isNotBlank(), modifier = Modifier.testTag("account_send_code")) {
                    if (form.busy) BusyIcon()
                    Text(stringResource(R.string.account_send_code))
                }
            }
            SignInStep.CODE -> {
                Text(stringResource(R.string.account_code_help, form.email), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("account_code_help"))
                OutlinedTextField(
                    value = form.code,
                    onValueChange = actions.onCodeChanged,
                    label = { Text(stringResource(R.string.account_code)) },
                    singleLine = true,
                    enabled = !form.busy,
                    isError = form.error != null,
                    supportingText = form.error?.let { e -> { ErrorText(e, form.retryAfterSeconds) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.onVerify() }),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth().testTag("account_code"),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions.onVerify, enabled = !form.busy && form.code.length == 6, modifier = Modifier.testTag("account_verify")) {
                        if (form.busy) BusyIcon()
                        Text(stringResource(R.string.account_verify))
                    }
                    TextButton(onClick = actions.onResendCode, enabled = !form.busy && form.resendInSeconds == 0, modifier = Modifier.testTag("account_resend")) {
                        Text(
                            if (form.resendInSeconds > 0) stringResource(R.string.account_resend_in, form.resendInSeconds)
                            else stringResource(R.string.account_resend),
                        )
                    }
                }
                TextButton(onClick = actions.onChangeEmail, enabled = !form.busy, modifier = Modifier.testTag("account_change_email")) {
                    Text(stringResource(R.string.account_change_email))
                }
            }
        }
    }
}

@Composable
private fun BusyIcon() {
    CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
}

@Composable
private fun ErrorText(error: SignInError, retryAfterSeconds: Long?) {
    val minutes = retryAfterSeconds?.let { ((it + 59) / 60).toInt().coerceAtLeast(1) }
    val text = when (error) {
        SignInError.INVALID_EMAIL -> stringResource(R.string.account_error_invalid_email)
        SignInError.WRONG_CODE -> stringResource(R.string.account_error_wrong_code)
        SignInError.TOO_MANY_ATTEMPTS -> stringResource(R.string.account_error_too_many_attempts)
        SignInError.LOCKED -> minutes?.let { pluralStringResource(R.plurals.account_error_locked_minutes, it, it) } ?: stringResource(R.string.account_error_locked)
        SignInError.RATE_LIMITED -> minutes?.let { pluralStringResource(R.plurals.account_error_rate_limited_minutes, it, it) } ?: stringResource(R.string.account_error_rate_limited)
        SignInError.TEST_ADDRESS -> stringResource(R.string.account_error_test_address)
        SignInError.NETWORK -> stringResource(R.string.account_error_network)
        SignInError.SERVER -> stringResource(R.string.account_error_server)
        SignInError.NOT_CONFIGURED -> stringResource(R.string.account_needs_url)
    }
    Text(text, modifier = Modifier.testTag("account_error").semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun SyncPanel(state: AccountUiState, mode: BackendMode, actions: AccountActions) {
    val account = state.account
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("account_who"),
    ) {
        Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Column {
            Text(
                if (mode == BackendMode.FAKE) stringResource(R.string.account_fake)
                else account.email?.let { stringResource(R.string.account_signed_in_as, it) } ?: stringResource(R.string.account_signed_in),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (mode == BackendMode.FAKE) {
                Text(stringResource(R.string.account_fake_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    SyncStatusCard(account.lastSync, account.syncing)
    if (mode == BackendMode.REMOTE && account.otherTrips > 0) OtherTripsRow(account.otherTrips, actions)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = actions.onSyncNow, enabled = !account.syncing, modifier = Modifier.testTag("account_sync_now")) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.account_sync_now))
        }
        if (mode == BackendMode.REMOTE) {
            OutlinedButton(onClick = actions.onSignOutRequested, modifier = Modifier.testTag("account_sign_out")) {
                Text(stringResource(R.string.account_sign_out))
            }
        }
    }
}

/** Trips on the phone that don't sync to this account, with the choice offered again. */
@Composable
private fun OtherTripsRow(count: Int, actions: AccountActions) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("account_other_trips")) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluralStringResource(R.plurals.account_other_trips_row, count, count), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onOtherTripsChoose, modifier = Modifier.testTag("account_other_trips_choose")) {
                Text(stringResource(R.string.account_other_trips_choose))
            }
        }
    }
}

@Composable
private fun SyncStatusCard(last: SyncStatus?, syncing: Boolean) {
    val failed = last != null && !last.ok
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth().testTag("account_status"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    when {
                        failed -> Icons.Filled.Warning
                        last == null -> Icons.Filled.Info
                        else -> Icons.Filled.CheckCircle
                    },
                    contentDescription = null,
                    tint = when {
                        failed -> MaterialTheme.colorScheme.error
                        last == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    when {
                        last == null -> stringResource(R.string.account_never_synced)
                        last.ok -> stringResource(R.string.account_last_sync, formatTime(last.atMillis))
                        else -> stringResource(R.string.account_last_sync_failed, formatTime(last.atMillis))
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("account_last_sync"),
                )
            }
            if (last != null && last.ok) {
                Text(
                    pluralStringResource(R.plurals.account_sync_counts, last.uploadedTrips, last.uploadedTrips, last.pulledTrips),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (failed) {
                Text(
                    syncErrorText(last!!.error) + if (last.willRetry) " " + stringResource(R.string.account_will_retry) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("account_sync_error"),
                )
            }
            if (syncing) {
                Text(stringResource(R.string.account_syncing), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("account_syncing"))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun syncErrorText(error: SyncError?): String = stringResource(
    when (error) {
        SyncError.NETWORK -> R.string.account_sync_error_network
        SyncError.SERVER_BUSY -> R.string.account_sync_error_busy
        SyncError.SIGNED_OUT -> R.string.account_sync_error_signed_out
        SyncError.REJECTED -> R.string.account_sync_error_rejected
        SyncError.RECOVERY -> R.string.account_sync_error_recovery
        SyncError.UNKNOWN, null -> R.string.account_sync_error_unknown
    },
)

@Composable
private fun formatTime(millis: Long): String {
    val context = LocalContext.current
    val date = Date(millis)
    return DateFormat.getMediumDateFormat(context).format(date) + ", " + DateFormat.getTimeFormat(context).format(date)
}
