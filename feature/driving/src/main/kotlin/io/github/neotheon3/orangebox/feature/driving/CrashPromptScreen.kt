package io.github.neotheon3.orangebox.feature.driving

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neotheon3.orangebox.core.dashboardui.DashboardColors
import io.github.neotheon3.orangebox.core.dashboardui.DashboardIcons
import io.github.neotheon3.orangebox.core.driving.crash.CrashPrompt
import io.github.neotheon3.orangebox.core.driving.crash.CrashTrigger

/**
 * Hosts the crash prompt over whatever is on screen. Timeout opens the dialer (the user still presses
 * call). While an impact prompt is up the activity may show over the lock screen.
 */
@Composable
fun CrashPromptOverlay(controller: CrashPromptController) {
    val prompt by controller.prompt.collectAsStateWithLifecycle()
    val targets by controller.targets.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val p = prompt ?: return
    ShowWhenLocked(p.trigger != CrashTrigger.MANUAL)
    LaunchedEffect(p.id, p.timedOut) {
        if (p.timedOut) controller.dial(context, p.id)
    }
    CrashPromptScreen(
        prompt = p,
        targets = targets,
        onCancel = controller::cancel,
        onCall = { controller.dial(context, p.id) },
        onSms = { controller.sms(context, p.id) },
    )
}

/**
 * Full-screen crash / emergency prompt in the dashboard's dark style (recreated art: the hazard
 * triangle is Orange Box's own vector). Impact: countdown ring, "I'm OK" first. Manual: no countdown.
 */
@Composable
fun CrashPromptScreen(
    prompt: CrashPrompt,
    targets: EmergencyTargets,
    onCancel: () -> Unit,
    onCall: () -> Unit,
    onSms: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)
    val manual = prompt.trigger == CrashTrigger.MANUAL
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            // Swallow touches so nothing underneath reacts (without merging the prompt's semantics).
            .pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("crash_prompt"),
        contentAlignment = Alignment.Center,
    ) {
        // Landscape (the dashboard's usual orientation): message and countdown left, the actions right.
        if (maxWidth > maxHeight && maxWidth >= 560.dp) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { Message(prompt, targets, manual, compact = true) }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { Actions(targets, manual, onCancel, onCall, onSms) }
            }
        } else {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Message(prompt, targets, manual, compact = false)
                Actions(targets, manual, onCancel, onCall, onSms)
            }
        }
    }
}

@Composable
private fun Message(prompt: CrashPrompt, targets: EmergencyTargets, manual: Boolean, compact: Boolean) {
    Icon(DashboardIcons.Hazard, contentDescription = null, tint = DashboardColors.HazardRed, modifier = Modifier.size(if (compact) 48.dp else 64.dp))
    Text(
        stringResource(if (manual) R.string.crash_title_manual else R.string.crash_title_impact),
        color = DashboardColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.testTag("crash_title"),
    )
    Text(
        when {
            manual -> stringResource(R.string.crash_body_manual, targets.dialNumber)
            prompt.timedOut -> stringResource(R.string.crash_body_timed_out, targets.dialNumber)
            else -> stringResource(R.string.crash_body_impact, targets.dialNumber)
        },
        color = DashboardColors.Caption, fontSize = 17.sp, textAlign = TextAlign.Center,
    )
    if (!manual) Countdown(prompt, if (compact) 112.dp else 140.dp)
}

@Composable
private fun Actions(targets: EmergencyTargets, manual: Boolean, onCancel: () -> Unit, onCall: () -> Unit, onSms: () -> Unit) {
    Button(
        onClick = onCancel,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("crash_cancel"),
    ) { Text(stringResource(if (manual) R.string.crash_close else R.string.crash_im_ok), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    Button(
        onClick = onCall,
        colors = ButtonDefaults.buttonColors(containerColor = DashboardColors.HazardRed, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("crash_call"),
    ) { Text(stringResource(R.string.crash_call, targets.dialNumber), fontSize = 18.sp) }
    OutlinedButton(
        onClick = onSms,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DashboardColors.Text),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("crash_sms"),
    ) {
        Text(
            targets.smsName?.let { stringResource(R.string.crash_sms_to, it) } ?: stringResource(R.string.crash_sms),
            fontSize = 18.sp, textAlign = TextAlign.Center,
        )
    }
    Text(
        stringResource(R.string.crash_never_automatic), color = DashboardColors.TileTitle, fontSize = 13.sp, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Countdown(prompt: CrashPrompt, size: androidx.compose.ui.unit.Dp) {
    val total = prompt.countdownSeconds ?: return
    val left = prompt.remainingSeconds ?: total
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        CircularProgressIndicator(
            progress = { if (total == 0) 0f else left / total.toFloat() },
            color = DashboardColors.HazardRed,
            trackColor = DashboardColors.CardRim,
            strokeWidth = 10.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            left.toString(), color = DashboardColors.Text, fontSize = 56.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("crash_countdown").semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
