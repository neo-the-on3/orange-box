package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Diagnostics palette (docs/ui/LEGACY_OBD_UI.md §3, §5.1). */
object DiagnosticsColors {
    val LampOk = Color(0xFF0CB0DC)
    val LampFault = Color(0xFFF06860)
    val LampPressed = Color(0xFFB02420)
    val LampCaption = Color(0xFFCBCBCB)
    val MessageBar = Color(0xFF2E2E2E)
    val Refresh = Color(0xFF8CBC4C)
    val Progress = Color(0xFFFF4D4D)
    val ZoneIdle = Color(0xFF7FA6BF)
    val ZoneFault = Color(0xFFF06860)
    val Grid = Color(0xFF1C2A33)
}

enum class DtcLampState { OK, FAULT, PRESSED }

/**
 * A diagnostics group lamp (#100): a 48 dp rounded square in blue (no codes) or coral (codes; tappable),
 * darker red while pressed, with the white glyph and a 12 sp caption under it. The colour is never the
 * only cue: [contentDescription] carries the code count ("Ignition: 1 trouble code").
 */
@Composable
fun DtcLamp(
    icon: ImageVector,
    label: String,
    faulted: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    /** Forces the pressed look (screenshots). */
    forcePressed: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val state = when {
        faulted && (pressed || forcePressed) -> DtcLampState.PRESSED
        faulted -> DtcLampState.FAULT
        else -> DtcLampState.OK
    }
    Column(
        modifier
            .widthIn(min = size + 12.dp)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                if (faulted) {
                    role = Role.Button
                    onClick { onClick(); true }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DtcLampFace(
            icon, state,
            Modifier.then(if (faulted) Modifier.clickable(interaction, indication = null, onClick = onClick) else Modifier),
            size = size,
        )
        BasicText(
            label,
            style = TextStyle(color = DiagnosticsColors.LampCaption, fontSize = 12.sp, textAlign = TextAlign.Center),
            maxLines = 1,
        )
    }
}

/** Just the lamp square, e.g. for the group sheet title. */
@Composable
fun DtcLampFace(icon: ImageVector, state: DtcLampState, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val base = when (state) {
        DtcLampState.OK -> DiagnosticsColors.LampOk
        DtcLampState.FAULT -> DiagnosticsColors.LampFault
        DtcLampState.PRESSED -> DiagnosticsColors.LampPressed
    }
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(base, Color.White, 0.18f), base, lerp(base, Color.Black, 0.22f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.35f))), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}
