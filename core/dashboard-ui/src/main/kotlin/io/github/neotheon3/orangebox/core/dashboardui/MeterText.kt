package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Big meter readout (RPM, speed) with its unit underneath, right-aligned like the legacy cluster.
 * The number auto-fits between [DashboardType.MeterMin] and [max] so 200 % font scale never clips.
 * [spoken] is the full content description ("Speed 59 kilometres per hour").
 */
@Composable
fun MeterText(
    value: String,
    unit: String,
    spoken: String,
    modifier: Modifier = Modifier,
    max: TextUnit = DashboardType.MeterMax,
    alignment: Alignment.Horizontal = Alignment.End,
) {
    Column(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            value,
            // Weighted so the unit below is always measured first and never squeezed out.
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            style = TextStyle(
                color = DashboardColors.Text,
                fontWeight = FontWeight.Bold,
                textAlign = if (alignment == Alignment.Start) TextAlign.Start else if (alignment == Alignment.End) TextAlign.End else TextAlign.Center,
                lineHeight = max,
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = DashboardType.MeterMin, maxFontSize = max, stepSize = 2.sp),
        )
        Text(unit, color = DashboardColors.Text, fontSize = DashboardType.MeterUnit, maxLines = 1)
    }
}
