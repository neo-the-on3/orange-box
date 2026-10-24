package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tile state: [NO_DATA] and [UNSUPPORTED] show `---`; unsupported also dims to 60 %. */
enum class TileStatus { NO_DATA, UNSUPPORTED, NORMAL, WARNING }

private val TileShape = RoundedCornerShape(3.dp)

/**
 * Detailed Info tile (`#1b1b1b`, border `#3a3a3a`): title top-left, value + unit bottom-right. A large
 * tile adds an icon and a bottom glow: blue when normal, red on warning, none without data.
 */
@Composable
fun ValueTile(
    title: String,
    value: String,
    unit: String,
    status: TileStatus,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    large: Boolean = false,
) {
    val glow: Color? = when {
        !large -> null
        status == TileStatus.NORMAL -> DashboardColors.Normal
        status == TileStatus.WARNING -> DashboardColors.TileWarningIcon
        else -> null
    }
    val spokenState = when (status) {
        TileStatus.NO_DATA -> stringResource(R.string.dashboard_no_data)
        TileStatus.UNSUPPORTED -> stringResource(R.string.dashboard_not_supported)
        TileStatus.NORMAL -> "$value $unit".trim()
        TileStatus.WARNING -> "$value $unit".trim() + ", " + stringResource(R.string.dashboard_state_warning)
    }
    Column(
        modifier
            .clip(TileShape)
            .background(DashboardColors.TileFill)
            .drawBehind {
                if (glow != null) {
                    drawRect(
                        Brush.radialGradient(
                            listOf(glow.copy(alpha = 0.55f), glow.copy(alpha = 0.12f), Color.Transparent),
                            center = Offset(size.width * 0.45f, size.height * 1.05f),
                            radius = size.width * 0.55f,
                        ),
                    )
                }
            }
            .border(1.dp, DashboardColors.TileBorder, TileShape)
            .alpha(if (status == TileStatus.UNSUPPORTED) 0.6f else 1f)
            .clearAndSetSemantics { contentDescription = "$title, $spokenState" }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title, color = DashboardColors.TileTitle, fontSize = DashboardType.TileTitle,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon, contentDescription = null,
                    tint = when (status) {
                        TileStatus.WARNING -> DashboardColors.TileWarningIcon
                        TileStatus.NORMAL -> DashboardColors.Normal
                        else -> DashboardColors.LampOff
                    },
                    modifier = Modifier.size(if (large) 34.dp else 20.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            BasicText(
                value,
                modifier = Modifier.weight(1f),
                style = TextStyle(color = DashboardColors.Text, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = DashboardType.TileValue, stepSize = 1.sp),
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, color = DashboardColors.Text, fontSize = DashboardType.TileUnit, maxLines = 1)
            }
        }
    }
}
