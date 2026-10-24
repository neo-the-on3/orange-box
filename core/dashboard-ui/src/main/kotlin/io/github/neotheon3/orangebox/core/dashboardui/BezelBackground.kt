package io.github.neotheon3.orangebox.core.dashboardui

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Black dashboard background with the shallow top arch (fill `#232323`, rim `#545454`). The black fills
 * edge to edge (MainActivity is edge-to-edge), but the arch is the lower half of a wide ellipse centred
 * on [archTop] and clipped there, so nothing of it is drawn under the status bar (#143: centring it on
 * the inset without clipping let its upper half cover the status-bar icons). Callers pad their content
 * with the safe-drawing insets.
 */
@Composable
fun BezelBackground(
    modifier: Modifier = Modifier,
    archHeightFraction: Float = 0.16f,
    archTop: Dp = statusBarArchTop(),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.background(DashboardColors.Background)) {
        Canvas(Modifier.fillMaxSize()) {
            val top = archTop.toPx()
            val archW = size.width * 0.62f
            val archH = (size.height * archHeightFraction).coerceIn(40.dp.toPx(), 110.dp.toPx())
            val left = (size.width - archW) / 2
            val topLeft = Offset(left, top - archH)
            val ellipse = Size(archW, archH * 2)
            clipRect(top = top) {
                drawOval(
                    brush = Brush.verticalGradient(listOf(DashboardColors.Arch.copy(alpha = 0.6f), DashboardColors.Arch), startY = top, endY = top + archH),
                    topLeft = topLeft,
                    size = ellipse,
                )
                drawOval(color = DashboardColors.ArchRim, topLeft = topLeft, size = ellipse, style = Stroke(1.5.dp.toPx()))
            }
        }
        content()
    }
}

/**
 * Where the arch hangs from: the bottom of the status bar. Takes the largest top inset of status bars,
 * system bars and safe drawing (cutouts). If the window reports no top inset at all (insets not dispatched,
 * e.g. some API 25 setups) while the status bar has not merely been hidden, falls back to the platform
 * `status_bar_height` dimen so the arch still clears the icons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun statusBarArchTop(): Dp {
    val density = LocalDensity.current
    val inset = maxOf(
        WindowInsets.statusBars.getTop(density),
        WindowInsets.systemBars.getTop(density),
        WindowInsets.safeDrawing.getTop(density),
    )
    if (inset > 0) return with(density) { inset.toDp() }
    // Hidden status bar (immersive): the stable inset is still known, and the arch may use the top edge.
    if (WindowInsets.statusBarsIgnoringVisibility.getTop(density) > 0) return 0.dp
    return with(density) { statusBarHeightPx(LocalContext.current).toDp() }
}

@SuppressLint("DiscouragedApi", "InternalInsetResource")
internal fun statusBarHeightPx(context: Context): Int {
    val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
}
