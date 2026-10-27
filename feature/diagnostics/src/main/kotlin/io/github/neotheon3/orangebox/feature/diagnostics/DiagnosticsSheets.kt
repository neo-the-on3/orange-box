package io.github.neotheon3.orangebox.feature.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neotheon3.orangebox.core.dashboardui.DashboardColors
import io.github.neotheon3.orangebox.core.dashboardui.DiagnosticsColors
import io.github.neotheon3.orangebox.core.dashboardui.DtcLampFace
import io.github.neotheon3.orangebox.core.dashboardui.DtcLampState
import io.github.neotheon3.orangebox.core.database.DtcRecordEntity
import io.github.neotheon3.orangebox.core.diagnostics.DtcCatalog
import io.github.neotheon3.orangebox.core.diagnostics.DtcGroup
import io.github.neotheon3.orangebox.core.diagnostics.DtcStatus
import io.github.neotheon3.orangebox.core.diagnostics.TroubleCode

private val SheetColor = Color(0xFF1A1A1A)
private val Secondary = Color(0xFFCCCCCC)

/** Group detail (§5.2): lamp + name, its codes with descriptions, the group text and earlier codes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupSheet(
    group: DtcGroup,
    codes: List<TroubleCode>,
    history: List<DtcRecordEntity>,
    formatTime: (Long) -> String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor,
        contentColor = Color.White,
        modifier = Modifier.testTag("dtc_group_sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DtcLampFace(group.icon, if (codes.isEmpty()) DtcLampState.OK else DtcLampState.FAULT, size = 40.dp)
                Text(
                    stringResource(group.labelRes), fontSize = 20.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 12.dp).semantics { heading() },
                )
            }
            codes.forEach { CodeRow(it) }
            HorizontalDivider(color = DashboardColors.CardRim)
            Text(DtcCatalog.groupDescription(group), color = Secondary, fontSize = 14.sp)
            Text(stringResource(R.string.dtc_recommendation), color = Secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            val earlier = history.filter { !it.isOpen && (DtcGroup.of(it.code) ?: DtcGroup.POWERTRAIN) == group }.take(10)
            if (earlier.isNotEmpty()) {
                Text(stringResource(R.string.dtc_earlier), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                earlier.forEach { RecordRow(it, formatTime) }
            }
        }
    }
}

@Composable
private fun CodeRow(code: TroubleCode) {
    Column(Modifier.fillMaxWidth().testTag("dtc_code_${code.code}_${code.status.wire}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(code.code, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            StatusChip(code.status)
        }
        Text(code.description, color = Secondary, fontSize = 14.sp)
    }
}

@Composable
private fun StatusChip(status: DtcStatus) {
    val color = when (status) {
        DtcStatus.STORED -> DiagnosticsColors.LampFault
        DtcStatus.PENDING -> DashboardColors.Warning
        DtcStatus.PERMANENT -> Color(0xFF9E9E9E)
    }
    Text(
        stringResource(status.labelRes).uppercase(),
        color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun RecordRow(r: DtcRecordEntity, formatTime: (Long) -> String) {
    val status = stringResource(DtcStatus.fromWire(r.kind).labelRes)
    val text = when {
        r.isOpen -> stringResource(R.string.dtc_record_open, r.code, status, formatTime(r.firstSeenAtMillis))
        r.endReason == DtcRecordEntity.END_CLEARED -> stringResource(R.string.dtc_record_cleared, r.code, status, formatTime(r.endedAtMillis!!))
        else -> stringResource(R.string.dtc_record_resolved, r.code, status, formatTime(r.endedAtMillis!!))
    }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("dtc_record_${r.id}")) {
        Text(text, fontSize = 14.sp)
        Text(DtcCatalog.describe(r.code), color = Secondary, fontSize = 12.sp)
    }
}

/** Everything recorded (#51), newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySheet(history: List<DtcRecordEntity>, formatTime: (Long) -> String, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetColor,
        contentColor = Color.White,
        modifier = Modifier.testTag("dtc_history_sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.diagnostics_history), fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
            if (history.isEmpty()) {
                Text(stringResource(R.string.history_empty), color = Secondary, fontSize = 14.sp, modifier = Modifier.testTag("dtc_history_empty"))
            }
            history.forEach { RecordRow(it, formatTime) }
        }
    }
}

/** Clear confirmation (§5.4, #49): Material 3 dialog with the legacy warning, extended. */
@Composable
internal fun ClearConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("clear_dialog"),
        title = { Text(stringResource(R.string.clear_title)) },
        text = { Text(stringResource(R.string.clear_body)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("clear_confirm")) { Text(stringResource(R.string.clear_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("clear_cancel")) { Text(stringResource(R.string.clear_cancel)) } },
    )
}

@Composable
internal fun ClearWhileMovingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("clear_moving_dialog"),
        title = { Text(stringResource(R.string.clear_moving_title)) },
        text = { Text(stringResource(R.string.clear_moving_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.clear_ok)) } },
    )
}
