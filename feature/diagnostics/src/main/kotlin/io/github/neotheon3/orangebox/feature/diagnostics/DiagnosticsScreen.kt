package io.github.neotheon3.orangebox.feature.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neotheon3.orangebox.core.dashboardui.DashboardColors
import io.github.neotheon3.orangebox.core.dashboardui.DashboardTheme
import io.github.neotheon3.orangebox.core.dashboardui.DiagnosticsColors
import io.github.neotheon3.orangebox.core.dashboardui.DtcLamp
import io.github.neotheon3.orangebox.core.diagnostics.DtcGroup
import io.github.neotheon3.orangebox.core.diagnostics.DtcStatus

@Composable
fun DiagnosticsRoute(onBack: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiagnosticsScreen(state, onBack = onBack, onRefresh = viewModel::refresh, onClear = viewModel::clear)
}

private enum class ClearDialog { CONFIRM, MOVING }

/**
 * Diagnostics (docs/ui/LEGACY_OBD_UI.md §5.1–5.4): the vehicle-systems illustration with Refresh and
 * "Clear stored DTC", ten group lamps and the message bar; landscape side by side, portrait stacked.
 * Always dark like the dashboard. A red lamp opens its group sheet; Clear asks first (#49).
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    formatTime: (Long) -> String = defaultTimeFormat(),
) {
    var group by rememberSaveable { mutableStateOf<DtcGroup?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var clearDialog by rememberSaveable { mutableStateOf<ClearDialog?>(null) }
    DashboardTheme {
        Box(modifier.fillMaxSize().background(DashboardColors.Background).testTag("diagnostics")) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopBar(onBack = onBack, onHistory = { showHistory = true })
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val landscape = maxWidth > maxHeight
                    val content = DiagnosticsContent(
                        state, formatTime,
                        onRefresh = onRefresh,
                        onClear = { clearDialog = if (state.moving) ClearDialog.MOVING else ClearDialog.CONFIRM },
                        onGroup = { group = it },
                    )
                    if (landscape) content.Landscape() else content.Portrait()
                }
            }
        }
        group?.let { g ->
            GroupSheet(
                group = g,
                codes = state.report?.codesIn(g).orEmpty(),
                history = state.history,
                formatTime = formatTime,
                onDismiss = { group = null },
            )
        }
        if (showHistory) HistorySheet(state.history, formatTime, onDismiss = { showHistory = false })
        when (clearDialog) {
            ClearDialog.CONFIRM -> ClearConfirmDialog(
                onConfirm = { clearDialog = null; group = null; onClear() },
                onDismiss = { clearDialog = null },
            )
            ClearDialog.MOVING -> ClearWhileMovingDialog(onDismiss = { clearDialog = null })
            null -> Unit
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, onHistory: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("diagnostics_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.diagnostics_back), tint = Color.White)
        }
        Text(
            stringResource(R.string.diagnostics_title),
            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
        )
        IconButton(onClick = onHistory, modifier = Modifier.testTag("diagnostics_history")) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.diagnostics_history), tint = Color.White)
        }
    }
}

private class DiagnosticsContent(
    val state: DiagnosticsUiState,
    val formatTime: (Long) -> String,
    val onRefresh: () -> Unit,
    val onClear: () -> Unit,
    val onGroup: (DtcGroup) -> Unit,
) {
    private val showClear get() = state.report?.hasClearableCodes == true

    @Composable
    fun Landscape() {
        Row(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    Illustration()
                    RefreshButton(Modifier.padding(15.dp).align(Alignment.TopStart))
                }
                if (showClear) ClearPill()
                Info()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Lamps()
                MessageBar()
            }
        }
    }

    @Composable
    fun Portrait() {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showClear) ClearPill(Modifier.align(Alignment.End))
            Box(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                Illustration()
                // Legacy portrait: Refresh overlaps the illustration's bottom edge by 24 dp.
                RefreshButton(Modifier.align(Alignment.BottomStart).padding(start = 15.dp).offset(y = 24.dp))
            }
            Spacer(Modifier.size(12.dp))
            Lamps()
            MessageBar()
            Info()
        }
    }

    @Composable
    private fun Illustration() {
        val zones = state.report?.faultedZones.orEmpty()
        VehicleSystemsIllustration(zones, illustrationA11y(zones), Modifier.fillMaxWidth().testTag("diagnostics_illustration"))
    }

    @Composable
    private fun RefreshButton(modifier: Modifier) {
        val label = stringResource(R.string.diagnostics_refresh)
        Box(
            modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFFA6D36A), DiagnosticsColors.Refresh, Color(0xFF5E8A2A))))
                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                .clickable(enabled = !state.busy, role = Role.Button, onClickLabel = label, onClick = onRefresh)
                .semantics { this.contentDescription = label }
                .testTag("diagnostics_refresh"),
            contentAlignment = Alignment.Center,
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }

    @Composable
    private fun ClearPill(modifier: Modifier = Modifier) {
        val shape = RoundedCornerShape(50)
        Box(
            modifier
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(DashboardColors.BezelTop, DashboardColors.BezelBottom)))
                .border(1.dp, DashboardColors.CardRim, shape)
                .clickable(enabled = !state.busy, role = Role.Button, onClick = onClear)
                .padding(horizontal = 22.dp)
                .testTag("diagnostics_clear"),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.diagnostics_clear), color = Color.White, fontSize = 12.sp)
        }
    }

    @Composable
    private fun Lamps() {
        Column(Modifier.fillMaxWidth().testTag("diagnostics_lamps"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DtcGroup.entries.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { g ->
                        val count = state.report?.codesIn(g)?.size ?: 0
                        DtcLamp(
                            icon = g.icon,
                            label = stringResource(g.labelRes),
                            faulted = count > 0,
                            contentDescription = groupA11y(g, count),
                            onClick = { onGroup(g) },
                            modifier = Modifier.testTag("dtc_lamp_${g.name.lowercase()}"),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MessageBar() {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 47.dp).background(DiagnosticsColors.MessageBar)
                .padding(horizontal = 19.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag("diagnostics_message"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(message(), color = Color.White, fontSize = 12.sp)
        }
    }

    @Composable
    private fun message(): String {
        val report = state.report
        val res = when {
            state.busy -> R.string.diagnostics_msg_reading
            state.notice != null -> when (state.notice) {
                DiagnosticsNotice.SCAN_FAILED -> R.string.diagnostics_msg_scan_failed
                DiagnosticsNotice.NOT_CONNECTED -> R.string.diagnostics_msg_not_connected
                DiagnosticsNotice.CLEARED -> R.string.diagnostics_msg_cleared
                DiagnosticsNotice.CLEAR_REJECTED -> R.string.diagnostics_msg_clear_rejected
                DiagnosticsNotice.CLEAR_FAILED -> R.string.diagnostics_msg_clear_failed
                DiagnosticsNotice.WHILE_MOVING -> R.string.clear_moving_body
            }
            report == null -> if (state.connected) R.string.diagnostics_msg_press_refresh else R.string.diagnostics_msg_not_connected
            report.codes.isNotEmpty() -> R.string.diagnostics_msg_codes
            report.milOn == true -> R.string.diagnostics_msg_mil_only
            else -> R.string.diagnostics_msg_good
        }
        return stringResource(res)
    }

    @Composable
    private fun Info() {
        val report = state.report ?: return
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val grey = Color(0xFFAAAAAA)
            report.vin?.let { Text(stringResource(R.string.diagnostics_vin, it), color = grey, fontSize = 12.sp, modifier = Modifier.testTag("diagnostics_vin")) }
            Text(stringResource(R.string.diagnostics_last_read, formatTime(report.readAtMillis)), color = grey, fontSize = 12.sp)
            if (report.unreadable.isNotEmpty()) {
                val lists = DtcStatus.entries.filter { it in report.unreadable }.map { stringResource(it.labelRes).lowercase() }.joinToString(", ")
                Text(stringResource(R.string.diagnostics_unreadable, lists), color = grey, fontSize = 12.sp, modifier = Modifier.testTag("diagnostics_unreadable"))
            }
            // A list the car doesn't implement (usually permanent codes on older cars) isn't a fault (#196).
            if (report.unsupported.isNotEmpty()) {
                val lists = DtcStatus.entries.filter { it in report.unsupported }.map { stringResource(it.labelRes).lowercase() }.joinToString(", ")
                Text(
                    stringResource(R.string.diagnostics_unsupported, lists), color = grey.copy(alpha = 0.7f), fontSize = 11.sp,
                    modifier = Modifier.testTag("diagnostics_unsupported"),
                )
            }
        }
    }
}
