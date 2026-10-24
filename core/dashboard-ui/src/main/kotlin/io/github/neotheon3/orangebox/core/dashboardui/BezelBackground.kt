package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Black dashboard background with the shallow top arch (fill `#232323`, rim `#545454`). The black fills
 * edge to edge, but the arch hangs from [archTop] (by default the bottom of the status bar / display
 * cutout), so it never runs under the status-bar icons; callers pad their content with the safe-drawing insets.
 */
@Composable
fun BezelBackground(
    modifier: Modifier = Modifier,
    archHeightFraction: Float = 0.16f,
    archTop: Dp = WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
        .asPaddingValues().calculateTopPadding(),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(DashboardColors.Background)) {
        Canvas(Modifier.fillMaxSize()) {
            val archW = size.width * 0.62f
            val archH = (size.height * archHeightFraction).coerceIn(40.dp.toPx(), 110.dp.toPx())
            val left = (size.width - archW) / 2
            // A wide ellipse whose lower half peeks below the status bar.
            val topLeft = Offset(left, archTop.toPx() - archH)
            val ellipse = Size(archW, archH * 2)
            drawOval(
                brush = Brush.verticalGradient(listOf(DashboardColors.Arch.copy(alpha = 0.6f), DashboardColors.Arch), startY = 0f, endY = archH),
                topLeft = topLeft,
                size = ellipse,
            )
            drawOval(color = DashboardColors.ArchRim, topLeft = topLeft, size = ellipse, style = Stroke(1.5.dp.toPx()))
        }
        content()
    }
}
