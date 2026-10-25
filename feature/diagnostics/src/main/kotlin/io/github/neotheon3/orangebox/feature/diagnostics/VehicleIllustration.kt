package io.github.neotheon3.orangebox.feature.diagnostics

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.neotheon3.orangebox.core.dashboardui.DiagnosticsColors
import io.github.neotheon3.orangebox.core.diagnostics.VehicleZone

/*
 * Vehicle-systems illustration (#100, docs/ui/LEGACY_OBD_UI.md §5.1).
 *
 * Provenance: drawn in code for Orange Box on a 290 × 163 design grid (the legacy view's size, which
 * is the only thing taken from the legacy layout). The car is a generic side profile built from our
 * own straight lines and arcs; the legacy layer bitmaps and the composite render were not traced,
 * sampled or used as an underlay.
 */

/** Design grid of the illustration. */
internal const val ILLUSTRATION_W = 290f
internal const val ILLUSTRATION_H = 163f

private const val GROUND_Y = 138f
private const val WHEEL_R = 19f
private val FRONT_WHEEL = Offset(72f, GROUND_Y - WHEEL_R)
private val REAR_WHEEL = Offset(218f, GROUND_Y - WHEEL_R)

/** Body silhouette, facing left. */
private fun bodyPath(): Path = Path().apply {
    moveTo(20f, 112f)
    lineTo(24f, 94f) // front bumper up to the hood
    lineTo(40f, 88f)
    lineTo(98f, 82f) // hood
    lineTo(132f, 52f) // windscreen
    lineTo(192f, 48f) // roof
    lineTo(236f, 76f) // rear window
    lineTo(262f, 82f) // boot lid
    lineTo(270f, 92f)
    lineTo(270f, 114f) // rear bumper
    lineTo(REAR_WHEEL.x + WHEEL_R + 5f, 114f)
    arcTo(rectAround(REAR_WHEEL, WHEEL_R + 5f), 0f, -180f, false)
    lineTo(FRONT_WHEEL.x + WHEEL_R + 5f, 114f)
    arcTo(rectAround(FRONT_WHEEL, WHEEL_R + 5f), 0f, -180f, false)
    lineTo(20f, 114f)
    close()
}

private fun rectAround(c: Offset, r: Float) = androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)

/** Wireframe detail lines (windows, doors, sill) drawn over the body. */
private fun detailPath(): Path = Path().apply {
    moveTo(106f, 82f); lineTo(134f, 58f); lineTo(160f, 56f); lineTo(160f, 82f); close() // front window
    moveTo(166f, 56f); lineTo(190f, 55f); lineTo(222f, 78f); lineTo(166f, 80f); close() // rear window
    moveTo(100f, 84f); lineTo(102f, 112f) // A pillar / front door edge
    moveTo(163f, 84f); lineTo(163f, 112f) // B pillar
    moveTo(226f, 82f); lineTo(224f, 104f) // rear door edge
    moveTo(40f, 100f); lineTo(266f, 98f) // belt line
}

private class Zone(val zone: VehicleZone, val draw: DrawScope.(color: Color, fill: Color) -> Unit)

private val stroke = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val dashed = Stroke(width = 1.4f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))

private fun DrawScope.box(l: Float, t: Float, r: Float, b: Float, color: Color, fill: Color, corner: Float = 3f) {
    drawRoundRect(fill, Offset(l, t), Size(r - l, b - t), CornerRadius(corner))
    drawRoundRect(color, Offset(l, t), Size(r - l, b - t), CornerRadius(corner), style = stroke)
}

private val zones = listOf(
    Zone(VehicleZone.NETWORK) { c, _ ->
        // CAN bus running from the engine bay, past the control module, to the rear.
        val p = Path().apply {
            moveTo(60f, 90f); lineTo(112f, 76f); lineTo(168f, 70f); lineTo(232f, 84f); lineTo(258f, 90f)
        }
        drawPath(p, c, style = dashed)
        listOf(Offset(60f, 90f), Offset(168f, 70f), Offset(258f, 90f)).forEach { drawCircle(c, 2.6f, it) }
    },
    Zone(VehicleZone.EXHAUST) { c, f ->
        drawLine(c, Offset(96f, 128f), Offset(234f, 128f), strokeWidth = 3f, cap = StrokeCap.Round)
        box(234f, 122f, 262f, 134f, c, f, corner = 6f) // silencer
        drawLine(c, Offset(262f, 128f), Offset(276f, 126f), strokeWidth = 3f, cap = StrokeCap.Round)
        box(112f, 123f, 136f, 133f, c, f, corner = 5f) // catalytic converter
    },
    Zone(VehicleZone.ENGINE) { c, f ->
        // Above the front wheel (its top is at y 100), under the hood.
        box(36f, 85f, 98f, 98f, c, f)
        for (i in 0..3) drawLine(c, Offset(46f + i * 14f, 88f), Offset(46f + i * 14f, 95f), strokeWidth = 1.4f)
    },
    Zone(VehicleZone.TRANSMISSION) { c, f ->
        box(100f, 96f, 126f, 112f, c, f)
        drawLine(c, Offset(126f, 108f), Offset(REAR_WHEEL.x - 4f, REAR_WHEEL.y - 2f), strokeWidth = 2.2f, cap = StrokeCap.Round)
    },
    Zone(VehicleZone.ELECTRONIC_CIRCUIT) { c, f ->
        box(104f, 64f, 122f, 78f, c, f, corner = 2f)
        for (i in 0..2) {
            val x = 108f + i * 5f
            drawLine(c, Offset(x, 61f), Offset(x, 64f), strokeWidth = 1.2f)
            drawLine(c, Offset(x, 78f), Offset(x, 81f), strokeWidth = 1.2f)
        }
    },
    Zone(VehicleZone.ELECTRONIC_DEVICE) { c, f ->
        box(22f, 95f, 34f, 102f, c, f, corner = 2f) // headlamp
        box(262f, 86f, 270f, 96f, c, f, corner = 2f) // tail lamp
        box(130f, 66f, 146f, 76f, c, f, corner = 2f) // dash display
    },
    Zone(VehicleZone.CHASSIS) { c, f ->
        listOf(FRONT_WHEEL, REAR_WHEEL).forEach { w ->
            drawCircle(f, WHEEL_R, w)
            drawCircle(c, WHEEL_R, w, style = Stroke(width = 2.4f))
            drawCircle(c, WHEEL_R * 0.45f, w, style = stroke)
            drawCircle(c, 2.4f, w)
        }
        drawLine(c, Offset(FRONT_WHEEL.x, FRONT_WHEEL.y), Offset(REAR_WHEEL.x, REAR_WHEEL.y), strokeWidth = 1.2f)
    },
)

/** Grid backdrop of the illustration. */
private fun DrawScope.grid() {
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF14212A), Color(0xFF070B0E))),
        size = Size(ILLUSTRATION_W, ILLUSTRATION_H), cornerRadius = CornerRadius(6f),
    )
    var x = 0f
    while (x <= ILLUSTRATION_W) { drawLine(DiagnosticsColors.Grid, Offset(x, 0f), Offset(x, ILLUSTRATION_H), strokeWidth = 0.8f); x += 14.5f }
    var y = 0f
    while (y <= ILLUSTRATION_H) { drawLine(DiagnosticsColors.Grid, Offset(0f, y), Offset(ILLUSTRATION_W, y), strokeWidth = 0.8f); y += 14.5f }
    drawLine(DiagnosticsColors.ZoneIdle.copy(alpha = 0.35f), Offset(8f, GROUND_Y), Offset(ILLUSTRATION_W - 8f, GROUND_Y), strokeWidth = 1f)
}

private fun DrawScope.inDesignGrid(block: DrawScope.() -> Unit) {
    val s = size.width / ILLUSTRATION_W
    withTransform({ scale(s, s, pivot = Offset.Zero) }, block)
}

private fun DrawScope.car(outline: Color, fill: Color) {
    val body = bodyPath()
    drawPath(body, fill)
    drawPath(body, outline, style = stroke)
    drawPath(detailPath(), outline.copy(alpha = outline.alpha * 0.6f), style = Stroke(width = 1f))
}

/**
 * The wireframe car over a grid with the seven system zones; zones in [faulted] turn red.
 * One accessibility node: "Vehicle systems: faults in engine and exhaust".
 */
@Composable
fun VehicleSystemsIllustration(faulted: Set<VehicleZone>, description: String, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(ILLUSTRATION_W / ILLUSTRATION_H).semantics { contentDescription = description }) {
        inDesignGrid {
            grid()
            car(DiagnosticsColors.ZoneIdle.copy(alpha = 0.8f), DiagnosticsColors.ZoneIdle.copy(alpha = 0.06f))
            for (z in zones) {
                val bad = z.zone in faulted
                val color = if (bad) DiagnosticsColors.ZoneFault else DiagnosticsColors.ZoneIdle
                val fill = if (bad) DiagnosticsColors.ZoneFault.copy(alpha = 0.45f) else DiagnosticsColors.ZoneIdle.copy(alpha = 0.12f)
                z.draw(this, color, fill)
            }
        }
    }
}

/** How the drive-start prompt's car looks. */
enum class ScanLook { SCANNING, OK, WARNING }

/**
 * The prompt's car silhouette with a scan beam sweeping across it (#50). With [reduceMotion] the beam
 * is not drawn and the car is shown statically.
 */
@Composable
fun ScanSweep(look: ScanLook, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val sweep = if (look == ScanLook.SCANNING && !reduceMotion) {
        val t = rememberInfiniteTransition(label = "scan")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
        v
    } else {
        null
    }
    Canvas(modifier.aspectRatio(ILLUSTRATION_W / ILLUSTRATION_H)) {
        inDesignGrid {
            val base = when (look) {
                ScanLook.WARNING -> DiagnosticsColors.ZoneFault
                ScanLook.OK -> DiagnosticsColors.LampOk
                ScanLook.SCANNING -> DiagnosticsColors.ZoneIdle
            }
            car(base.copy(alpha = 0.9f), base.copy(alpha = if (look == ScanLook.SCANNING) 0.06f else 0.18f))
            listOf(FRONT_WHEEL, REAR_WHEEL).forEach { drawCircle(base, WHEEL_R, it, style = Stroke(width = 2.4f)) }
            if (sweep != null) {
                val x = 10f + sweep * (ILLUSTRATION_W - 20f)
                // Already-scanned part glows, the beam itself is a bright line with a soft trail.
                clipRect(right = x) { car(Color.White.copy(alpha = 0.9f), DiagnosticsColors.LampOk.copy(alpha = 0.18f)) }
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, DiagnosticsColors.LampOk.copy(alpha = 0.35f)), startX = x - 40f, endX = x),
                    topLeft = Offset(x - 40f, 30f), size = Size(40f, GROUND_Y - 22f),
                )
                drawLine(Color.White, Offset(x, 30f), Offset(x, GROUND_Y + 6f), strokeWidth = 1.6f)
            }
        }
    }
}
