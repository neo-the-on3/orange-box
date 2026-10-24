package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle

/** Minimum bottom-bar target (issue #98: ≥ 56 dp). */
val BottomBarButtonSize: Dp = 56.dp

private val bezel = Brush.verticalGradient(listOf(DashboardColors.BezelTop, DashboardColors.BezelBottom))

/** Round bezel button (hazard, power, diagnostics), 56 dp. */
@Composable
fun BottomBarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = DashboardColors.Text,
    faceColor: Color = Color(0xFF151515),
) {
    Box(
        modifier
            .size(BottomBarButtonSize)
            .clip(CircleShape)
            .background(bezel)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(5.dp)
            .background(faceColor, CircleShape)
            .border(1.dp, DashboardColors.CardRim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
    }
}

/** Pill toggle ("Detailed Info."): blue `#0448c0` face while [selected]. */
@Composable
fun BottomBarToggle(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    stateOn: String,
    stateOff: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .defaultMinSize(minWidth = 150.dp, minHeight = BottomBarButtonSize)
            .clip(shape)
            .background(bezel)
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics { stateDescription = if (selected) stateOn else stateOff }
            .padding(4.dp)
            .background(if (selected) DashboardColors.DetailButtonOn else Color(0xFF151515), RoundedCornerShape(9.dp))
            .border(1.dp, DashboardColors.CardRim, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = DashboardColors.Text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        BasicText(
            label,
            style = TextStyle(color = DashboardColors.Text, fontWeight = FontWeight.Medium),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = with(LocalDensity.current) { 10.dp.toSp() }, maxFontSize = 16.sp, stepSize = 0.5.sp),
        )
    }
}

/** Pager position dots: active accent blue, inactive grey. Decorative for TalkBack (pages are announced). */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(count) { i ->
            Box(Modifier.size(8.dp).background(if (i == current) DashboardColors.Accent else DashboardColors.PageDotOff, CircleShape))
        }
    }
}
