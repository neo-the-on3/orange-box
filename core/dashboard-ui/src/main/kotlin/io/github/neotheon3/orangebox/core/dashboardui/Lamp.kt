package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle

/** What a lamp shows. [color] is used when [on]; [stateLabel] overrides the spoken state. */
data class LampState(val on: Boolean, val color: Color, val stateLabel: String? = null)

/**
 * Indicator lamp: icon above a caps caption. Off is grey `#686868`; on uses the lamp colour and a bold
 * caption, so the state is not carried by colour alone. Not interactive; one merged node whose
 * description includes the state ("Coolant, warning").
 */
@Composable
fun Lamp(
    icon: ImageVector,
    label: String,
    state: LampState,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
) {
    val spoken = state.stateLabel ?: stringResource(if (state.on) R.string.dashboard_state_on else R.string.dashboard_state_off)
    val tint = if (state.on) state.color else DashboardColors.LampOff
    val minCaption = with(LocalDensity.current) { 7.dp.toSp() }
    Column(
        modifier.clearAndSetSemantics { contentDescription = "$label, $spoken" }.padding(horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        BasicText(
            label.uppercase(),
            style = TextStyle(
                color = if (state.on) DashboardColors.Text else DashboardColors.LampOff,
                fontWeight = if (state.on) FontWeight.Black else FontWeight.Bold,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = minCaption, maxFontSize = DashboardType.Caption, stepSize = 0.5.sp),
        )
    }
}

/** Panel header lamp ("FUEL-CUT", "OVERSPEED"): icon + 14 sp caps title, lit in [state] colour. */
@Composable
fun HeaderLamp(icon: ImageVector, label: String, state: LampState, modifier: Modifier = Modifier) {
    val spoken = state.stateLabel ?: stringResource(if (state.on) R.string.dashboard_state_active else R.string.dashboard_state_off)
    val tint = if (state.on) state.color else DashboardColors.LampOff
    Row(
        modifier.clearAndSetSemantics { contentDescription = "$label, $spoken" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        BasicText(
            label.uppercase(),
            modifier = Modifier.weight(1f, fill = false),
            // Lit: white text (readable on the matching glow), icon in the lamp colour.
            style = TextStyle(
                color = if (state.on) DashboardColors.Text else DashboardColors.LampOff,
                fontWeight = if (state.on) FontWeight.Black else FontWeight.Bold,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = with(LocalDensity.current) { 9.dp.toSp() }, maxFontSize = DashboardType.Header, stepSize = 0.5.sp,
            ),
        )
    }
}
