package io.github.neotheon3.orangebox.feature.setup

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

data class PermissionRow(val permission: AppPermission, val status: PermissionStatus)

/** Stateful: computes statuses, requests permissions and re-checks when the user returns from Settings. */
@Composable
fun PermissionsSection(
    askedPermissions: Set<String>,
    onAsked: (Collection<String>) -> Unit,
    modifier: Modifier = Modifier,
    permissions: List<AppPermission> = AppPermission.forDevice(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val rows = remember(refresh, askedPermissions, permissions) {
        permissions.map { PermissionRow(it, PermissionResolver.status(activity, context, it, askedPermissions)) }
    }
    PermissionsPanel(
        rows = rows,
        onRequest = { p ->
            onAsked(p.permissions)
            launcher.launch(p.permissions.toTypedArray())
        },
        onOpenSettings = { context.startActivity(PermissionResolver.appSettingsIntent(context)) },
        modifier = modifier,
    )
}

/** Stateless list of permission rows (Material 3 list items). */
@Composable
fun PermissionsPanel(
    rows: List<PermissionRow>,
    onRequest: (AppPermission) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (rows.isEmpty()) {
            Text(stringResource(R.string.permissions_none_needed), modifier = Modifier.testTag("permissions_none"))
        }
        rows.forEach { row ->
            val title = stringResource(row.permission.titleRes())
            val statusText = stringResource(row.status.labelRes())
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = {
                    Column {
                        Text(stringResource(row.permission.rationaleRes()))
                        Text(
                            statusText + if (!row.permission.required) " · " + stringResource(R.string.permission_optional) else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (row.status == PermissionStatus.GRANTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingContent = {
                    Icon(
                        if (row.status == PermissionStatus.GRANTED) Icons.Filled.CheckCircle else Icons.Filled.Info,
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    when (row.status) {
                        PermissionStatus.GRANTED -> Unit
                        PermissionStatus.PERMANENTLY_DENIED -> OutlinedButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("permission_settings_${row.permission.name.lowercase()}"),
                        ) { Text(stringResource(R.string.permission_open_settings)) }
                        else -> FilledTonalButton(
                            onClick = { onRequest(row.permission) },
                            modifier = Modifier.testTag("permission_allow_${row.permission.name.lowercase()}"),
                        ) { Text(stringResource(R.string.permission_allow)) }
                    }
                },
                modifier = Modifier
                    .testTag("permission_row_${row.permission.name.lowercase()}")
                    .semantics(mergeDescendants = false) { contentDescription = "$title: $statusText" },
            )
        }
        if (rows.any { it.status == PermissionStatus.PERMANENTLY_DENIED }) {
            Text(
                stringResource(R.string.permission_permanently_denied_help),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("permissions_settings_help"),
            )
        }
    }
}

internal fun AppPermission.titleRes() = when (this) {
    AppPermission.BLUETOOTH -> R.string.permission_bluetooth_title
    AppPermission.NOTIFICATIONS -> R.string.permission_notifications_title
    AppPermission.LOCATION -> R.string.permission_location_title
}

internal fun AppPermission.rationaleRes() = when (this) {
    AppPermission.BLUETOOTH -> R.string.permission_bluetooth_rationale
    AppPermission.NOTIFICATIONS -> R.string.permission_notifications_rationale
    AppPermission.LOCATION -> R.string.permission_location_rationale
}

internal fun PermissionStatus.labelRes() = when (this) {
    PermissionStatus.GRANTED -> R.string.permission_status_granted
    PermissionStatus.NOT_ASKED -> R.string.permission_status_not_asked
    PermissionStatus.DENIED -> R.string.permission_status_denied
    PermissionStatus.PERMANENTLY_DENIED -> R.string.permission_status_permanently_denied
}
