package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

private val CardShape = RoundedCornerShape(3.dp)

/** Centre-column card (FUEL, TRIP): `#1a1a1a` with a `#393939` rim and a small caps header. */
@Composable
fun CenterCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(DashboardColors.CardFill, CardShape)
            .border(1.dp, DashboardColors.CardRim, CardShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = DashboardColors.TileTitle, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                title.uppercase(), color = DashboardColors.TileTitle, fontSize = DashboardType.Caption,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 3.dp), thickness = 1.dp, color = DashboardColors.CardRim)
        content()
    }
}

/** Right-aligned value + small unit ("8.9 km/L"). */
@Composable
fun ValueWithUnit(value: String, unit: String, spoken: String, modifier: Modifier = Modifier) {
    val minValue = with(LocalDensity.current) { 12.dp.toSp() }
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicText(
            value,
            modifier = Modifier.weight(1f, fill = false),
            style = TextStyle(color = DashboardColors.Text, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = minValue, maxFontSize = DashboardType.Value, stepSize = 1.sp),
        )
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Text(unit, color = DashboardColors.Text, fontSize = DashboardType.ValueUnit, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/**
 * Instant economy bar, 0–[max] km/L (track `#1e253b`, fill `#819ff7`), with 0/10/20/30 tick labels.
 * [fraction] is clamped to 0..1.
 */
@Composable
fun FuelBar(fraction: Float, modifier: Modifier = Modifier, max: Int = 30, showTicks: Boolean = true) {
    // Decorative: the economy value next to it carries the spoken reading.
    Column(modifier.fillMaxWidth().clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            drawRoundRect(DashboardColors.FuelTrack, cornerRadius = CornerRadius(1.dp.toPx()))
            val f = fraction.coerceIn(0f, 1f)
            if (f > 0f) {
                drawRoundRect(DashboardColors.FuelFill, size = Size(size.width * f, size.height), cornerRadius = CornerRadius(1.dp.toPx()))
            }
        }
        if (showTicks) Box(Modifier.fillMaxWidth().padding(top = 1.dp)) {
            val ticks = (0..max step 10).toList()
            ticks.forEachIndexed { i, t ->
                val align = when (i) {
                    0 -> Alignment.CenterStart
                    ticks.lastIndex -> Alignment.CenterEnd
                    else -> androidx.compose.ui.BiasAlignment(-1f + 2f * i / ticks.lastIndex, 0f)
                }
                Text(
                    "$t", color = DashboardColors.TileTitle, fontSize = DashboardType.Caption * 0.8f,
                    lineHeight = DashboardType.Caption * 0.9f, modifier = Modifier.align(align),
                )
            }
        }
    }
}
