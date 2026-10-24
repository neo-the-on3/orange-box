package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Bezel ring thickness: outer radius 32 dp, screen radius 26 dp (legacy §3). */
private val OuterRadius = 32.dp
private val ScreenRadius = 26.dp
private val ScreenInset = 9.dp

/**
 * Instrument cluster: a bezel ring around a glossy screen. The rim and inner wash follow [glow],
 * animated over 300 ms unless [DashboardSettings.reduceMotion]. [content] is laid out inside the
 * screen with [contentPadding].
 */
@Composable
fun ClusterPanel(
    glow: PanelGlow,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalDashboardSettings.current
    val spec = if (settings.reduceMotion) snap<Color>() else tween(300)
    val stroke by animateColorAsState(glow.stroke ?: Color.Transparent, spec, label = "panelStroke")
    val tint by animateColorAsState(glow.tint ?: Color.Transparent, spec, label = "panelTint")
    Box(
        modifier
            .drawBehind { drawCluster(stroke, tint, settings.highContrast) }
            .padding(ScreenInset + contentPadding),
        content = content,
    )
}

private fun DrawScope.drawCluster(glowStroke: Color, glowTint: Color, highContrast: Boolean) {
    val outer = OuterRadius.toPx()
    val inset = ScreenInset.toPx()
    val screenR = ScreenRadius.toPx()
    // Bezel ring.
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(DashboardColors.BezelTop, DashboardColors.BezelBottom)),
        cornerRadius = CornerRadius(outer),
    )
    val band = 4.dp.toPx()
    drawRoundRect(
        color = DashboardColors.BezelBand,
        topLeft = Offset(band, band),
        size = Size(size.width - 2 * band, size.height - 2 * band),
        cornerRadius = CornerRadius(outer - band),
    )
    val screenTopLeft = Offset(inset, inset)
    val screenSize = Size(size.width - 2 * inset, size.height - 2 * inset)
    // Outer glow: a few widening, fading strokes approximate a blur without RenderEffect (API 24).
    if (glowStroke.alpha > 0f) {
        listOf(9.dp to 0.12f, 6.dp to 0.22f, 3.5.dp to 0.4f).forEach { (w, a) ->
            drawRoundRect(
                color = glowStroke.copy(alpha = glowStroke.alpha * a),
                topLeft = screenTopLeft, size = screenSize, cornerRadius = CornerRadius(screenR),
                style = Stroke(w.toPx()),
            )
        }
    }
    // Screen.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(DashboardColors.ScreenTop, DashboardColors.ScreenBottom),
            startY = screenTopLeft.y, endY = screenTopLeft.y + screenSize.height,
        ),
        topLeft = screenTopLeft, size = screenSize, cornerRadius = CornerRadius(screenR),
    )
    val clip = Path().apply { addRoundRect(RoundRect(Rect(screenTopLeft, screenSize), CornerRadius(screenR))) }
    clipPath(clip) {
        if (glowTint.alpha > 0f) {
            drawRect(
                brush = Brush.radialGradient(
                    listOf(glowTint.copy(alpha = glowTint.alpha * 0.95f), glowTint.copy(alpha = glowTint.alpha * 0.35f)),
                    center = Offset(size.width / 2, size.height * 0.55f),
                    radius = maxOf(size.width, size.height) * 0.7f,
                ),
            )
        }
        if (!highContrast) {
            // Gloss on the upper half.
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    startY = screenTopLeft.y, endY = size.height * 0.5f,
                ),
            )
            // Dot grid.
            val step = 4.dp.toPx()
            val dot = Color.White.copy(alpha = 0.035f)
            var y = screenTopLeft.y + step / 2
            while (y < size.height) {
                var x = screenTopLeft.x + step / 2
                while (x < size.width) {
                    drawRect(dot, Offset(x, y), Size(1f, 1f))
                    x += step
                }
                y += step
            }
        }
    }
    // Rim.
    val rimWidth = (if (highContrast) 2.5.dp else 1.5.dp).toPx()
    if (glowStroke.alpha > 0f) {
        drawRoundRect(
            color = glowStroke, topLeft = screenTopLeft, size = screenSize,
            cornerRadius = CornerRadius(screenR), style = Stroke(rimWidth + 1.dp.toPx()),
        )
    } else {
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(DashboardColors.ScreenStrokeTop, DashboardColors.ScreenStrokeBottom),
                startY = screenTopLeft.y, endY = screenTopLeft.y + screenSize.height,
            ),
            topLeft = screenTopLeft, size = screenSize, cornerRadius = CornerRadius(screenR),
            style = Stroke(rimWidth), alpha = if (highContrast) 1f else 0.55f,
        )
    }
}
