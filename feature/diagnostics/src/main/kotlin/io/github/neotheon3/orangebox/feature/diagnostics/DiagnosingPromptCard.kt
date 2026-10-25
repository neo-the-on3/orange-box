package io.github.neotheon3.orangebox.feature.diagnostics

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.neotheon3.orangebox.core.dashboardui.DashboardColors
import io.github.neotheon3.orangebox.core.dashboardui.DiagnosticsColors
import io.github.neotheon3.orangebox.core.dashboardui.rememberReduceMotion
import io.github.neotheon3.orangebox.core.diagnostics.DiagnosingPrompt
import io.github.neotheon3.orangebox.core.diagnostics.DiagnosingPromptController
import kotlinx.coroutines.delay

/**
 * The "Car Diagnosing…" prompt at drive start (§5.3, #50) as a floating card, not a modal dialog: the
 * screen under it stays usable, and × closes it. Scanning shows our Canvas sweep over the car and a
 * thin red progress bar; with reduce motion the car is static and the bar steps instead of gliding.
 * "DTC Warning" opens Diagnostics from the car image (or the button, for TalkBack and keyboards).
 */
@Composable
fun DiagnosingPromptCard(
    prompt: DiagnosingPrompt,
    onDismiss: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = rememberReduceMotion(),
) {
    if (prompt == DiagnosingPrompt.Hidden) return
    val shape = RoundedCornerShape(8.dp)
    val title = stringResource(
        when (prompt) {
            DiagnosingPrompt.Scanning -> R.string.prompt_scanning
            DiagnosingPrompt.NoTroubles -> R.string.prompt_no_troubles
            else -> R.string.prompt_warning
        },
    )
    Column(
        modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xF01F1F1F))
            .border(1.dp, DashboardColors.CardRim, shape)
            .testTag("diagnosing_prompt"),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }.testTag("diagnosing_title"),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("diagnosing_close")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.prompt_close), tint = Color.White)
            }
        }
        val warning = prompt as? DiagnosingPrompt.Warning
        val look = when (prompt) {
            DiagnosingPrompt.Scanning -> ScanLook.SCANNING
            DiagnosingPrompt.NoTroubles -> ScanLook.OK
            else -> ScanLook.WARNING
        }
        val carLabel = if (warning != null) stringResource(R.string.prompt_open_diagnostics) else stringResource(R.string.prompt_car_scanning)
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .then(
                    if (warning != null) Modifier.clickable(role = Role.Button, onClickLabel = carLabel, onClick = onOpenDiagnostics)
                    else Modifier,
                )
                .semantics { contentDescription = carLabel }
                .testTag("diagnosing_car"),
        ) {
            ScanSweep(look, reduceMotion, Modifier.fillMaxWidth())
        }
        if (prompt == DiagnosingPrompt.Scanning) {
            ScanProgress(reduceMotion, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (prompt) {
                DiagnosingPrompt.NoTroubles -> Body(stringResource(R.string.diagnostics_msg_good))
                is DiagnosingPrompt.Warning -> {
                    if (prompt.codeCount > 0) Body(pluralStringResource(R.plurals.prompt_warning_codes, prompt.codeCount, prompt.codeCount))
                    if (prompt.milOn) Body(stringResource(R.string.prompt_warning_mil))
                    Body(stringResource(R.string.prompt_warning_body))
                    TextButton(onClick = onOpenDiagnostics, modifier = Modifier.align(Alignment.End).testTag("diagnosing_open")) {
                        Text(stringResource(R.string.prompt_open_diagnostics), color = DashboardColors.Accent)
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Body(text: String) = Text(text, color = Color(0xFFDDDDDD), fontSize = 13.sp)

/** 3 dp red bar filling over the minimum scan time; stepped (no gliding) with reduce motion. */
@Composable
private fun ScanProgress(reduceMotion: Boolean, modifier: Modifier) {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            for (step in 1..STEPS) {
                delay(DiagnosingPromptController.MIN_SCAN_MILLIS / STEPS)
                target = step / STEPS.toFloat()
            }
        } else {
            target = 1f
        }
    }
    val fraction by animateFloatAsState(
        target,
        if (reduceMotion) tween(0) else tween(DiagnosingPromptController.MIN_SCAN_MILLIS.toInt(), easing = LinearEasing),
        label = "scanProgress",
    )
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0xFF3A3A3A)).testTag("diagnosing_progress")) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(DiagnosticsColors.Progress))
    }
}

private const val STEPS = 4
