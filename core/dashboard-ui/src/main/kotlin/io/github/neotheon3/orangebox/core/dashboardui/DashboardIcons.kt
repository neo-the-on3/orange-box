package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Orange Box's own dashboard icon set, authored as path data on a 24×24 grid (provenance: drawn
 * for this project from simple geometric shapes; no legacy or third-party artwork). Icons are
 * single-colour and tinted by the caller.
 */
object DashboardIcons {
    val FuelDrop: ImageVector by lazy {
        icon("FuelDrop") {
            fill(PathFillType.EvenOdd) {
                moveTo(12f, 2.5f)
                curveTo(9f, 6.5f, 5.5f, 10.6f, 5.5f, 14.5f)
                arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 14.5f)
                curveTo(18.5f, 10.6f, 15f, 6.5f, 12f, 2.5f)
                close()
                // Highlight cut-out.
                moveTo(8.6f, 14.6f)
                curveTo(8.6f, 16.6f, 9.9f, 18f, 11.6f, 18.3f)
                lineTo(11.4f, 19.4f)
                curveTo(9.1f, 19f, 7.5f, 17.1f, 7.5f, 14.6f)
                close()
            }
        }
    }

    val OverspeedGauge: ImageVector by lazy {
        icon("OverspeedGauge") {
            stroke {
                moveTo(5.64f, 19.36f)
                arcTo(9f, 9f, 0f, true, true, 18.36f, 19.36f)
            }
            stroke { moveTo(12f, 13f); lineTo(16.6f, 8.4f) }
            stroke(1.6f) { moveTo(12f, 5.2f); lineTo(12f, 6.6f); moveTo(6.5f, 7.5f); lineTo(7.5f, 8.5f) }
            fill { circle(12f, 13f, 1.8f) }
        }
    }

    val Coolant: ImageVector by lazy {
        icon("Coolant") {
            stroke(2.2f) { moveTo(11f, 3f); lineTo(11f, 13f) }
            fill { circle(11f, 14.6f, 2.4f) }
            stroke(1.6f) {
                moveTo(13.5f, 5f); lineTo(16.5f, 5f)
                moveTo(13.5f, 8f); lineTo(16.5f, 8f)
                moveTo(13.5f, 11f); lineTo(15.5f, 11f)
            }
            stroke(1.6f) {
                moveTo(2.5f, 20f)
                quadToRelative(1.6f, -1.6f, 3.2f, 0f)
                reflectiveQuadToRelative(3.2f, 0f)
                reflectiveQuadToRelative(3.2f, 0f)
                reflectiveQuadToRelative(3.2f, 0f)
                reflectiveQuadToRelative(3.2f, 0f)
                reflectiveQuadToRelative(3.2f, 0f)
            }
        }
    }

    val Battery: ImageVector by lazy {
        icon("Battery") {
            stroke(1.8f) { moveTo(3f, 7.5f); lineTo(21f, 7.5f); lineTo(21f, 19f); lineTo(3f, 19f); close() }
            fill {
                moveTo(5.5f, 4.5f); lineTo(9f, 4.5f); lineTo(9f, 7f); lineTo(5.5f, 7f); close()
                moveTo(15f, 4.5f); lineTo(18.5f, 4.5f); lineTo(18.5f, 7f); lineTo(15f, 7f); close()
            }
            stroke(1.8f) {
                moveTo(5.8f, 12.8f); lineTo(9.6f, 12.8f)
                moveTo(14.4f, 12.8f); lineTo(18.2f, 12.8f)
                moveTo(16.3f, 10.9f); lineTo(16.3f, 14.7f)
            }
        }
    }

    val EcoLeaf: ImageVector by lazy {
        icon("EcoLeaf") {
            fill(PathFillType.EvenOdd) {
                moveTo(20f, 3.5f)
                curveTo(10.5f, 3.5f, 5f, 8.2f, 5f, 14.6f)
                curveTo(5f, 16.4f, 5.6f, 18f, 6.6f, 19.2f)
                curveTo(13.8f, 19.6f, 20f, 14.6f, 20f, 3.5f)
                close()
                // Vein.
                moveTo(7.6f, 17.4f)
                curveTo(10f, 13.4f, 13f, 10.4f, 16.6f, 7.6f)
                lineTo(17.2f, 8.3f)
                curveTo(13.8f, 11f, 10.9f, 14f, 8.6f, 17.9f)
                close()
            }
            stroke(1.8f) { moveTo(3.5f, 21f); lineTo(7f, 17.4f) }
        }
    }

    val AccelCar: ImageVector by lazy {
        icon("AccelCar") {
            group(scaleX = 0.8f, scaleY = 0.8f, translationX = 5.2f, translationY = 2.6f) { car() }
            stroke(1.6f) {
                moveTo(1.5f, 11f); lineTo(5f, 11f)
                moveTo(1f, 14f); lineTo(4.2f, 14f)
                moveTo(2f, 17f); lineTo(4.8f, 17f)
            }
        }
    }

    val BrakeCar: ImageVector by lazy {
        icon("BrakeCar") {
            group(scaleX = 0.8f, scaleY = 0.8f, translationX = -0.2f, translationY = 2.6f) { car() }
            stroke(1.8f) { moveTo(21.8f, 8.5f); lineTo(21.8f, 18.5f) }
            stroke(1.4f) { moveTo(1.5f, 21.5f); lineTo(6.5f, 21.5f); moveTo(10f, 21.5f); lineTo(15f, 21.5f) }
        }
    }

    val Idling: ImageVector by lazy {
        icon("Idling") {
            stroke(1.8f) { circle(12f, 12.5f, 8.5f) }
            stroke { moveTo(12f, 12.5f); lineTo(7.4f, 15.2f) }
            stroke(1.4f) {
                moveTo(12f, 5.4f); lineTo(12f, 6.8f)
                moveTo(17.1f, 7.4f); lineTo(16.1f, 8.4f)
                moveTo(6.9f, 7.4f); lineTo(7.9f, 8.4f)
            }
            fill { circle(12f, 12.5f, 1.6f) }
        }
    }

    val MilEngine: ImageVector by lazy {
        icon("MilEngine") {
            stroke(1.8f) {
                moveTo(3.5f, 10f); lineTo(5.5f, 10f); lineTo(5.5f, 8f); lineTo(8.5f, 8f); lineTo(8.5f, 6f)
                lineTo(14.5f, 6f); lineTo(14.5f, 8f); lineTo(17f, 8f); lineTo(19f, 10.5f); lineTo(21f, 10.5f)
                lineTo(21f, 16f); lineTo(19f, 16f); lineTo(17f, 18.5f); lineTo(8.5f, 18.5f); lineTo(6.5f, 16f)
                lineTo(3.5f, 16f); close()
            }
            stroke(1.8f) { moveTo(12.5f, 10f); lineTo(12.5f, 13.2f) }
            fill { circle(12.5f, 15.4f, 1f) }
        }
    }

    val ObdPlug: ImageVector by lazy {
        icon("ObdPlug") {
            stroke(1.8f) { moveTo(2.5f, 7.5f); lineTo(21.5f, 7.5f); lineTo(19.5f, 17f); lineTo(4.5f, 17f); close() }
            fill {
                listOf(6.5f, 9.25f, 12f, 14.75f, 17.5f).forEach { circle(it, 10.8f, 0.95f) }
                listOf(7.9f, 10.6f, 13.4f, 16.1f).forEach { circle(it, 13.8f, 0.95f) }
            }
        }
    }

    val Pin: ImageVector by lazy {
        icon("Pin") {
            fill(PathFillType.EvenOdd) {
                moveTo(12f, 2.5f)
                arcTo(6.5f, 6.5f, 0f, false, false, 5.5f, 9f)
                curveTo(5.5f, 13.8f, 12f, 21.5f, 12f, 21.5f)
                curveTo(12f, 21.5f, 18.5f, 13.8f, 18.5f, 9f)
                arcTo(6.5f, 6.5f, 0f, false, false, 12f, 2.5f)
                close()
                circle(12f, 9f, 2.4f)
            }
        }
    }

    val Clock: ImageVector by lazy {
        icon("Clock") {
            stroke(1.8f) { circle(12f, 12f, 9f) }
            stroke(1.8f) { moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f) }
        }
    }

    val FuelPump: ImageVector by lazy {
        icon("FuelPump") {
            fill(PathFillType.EvenOdd) {
                moveTo(4f, 4f); lineTo(13f, 4f); lineTo(13f, 20f); lineTo(4f, 20f); close()
                moveTo(6f, 6f); lineTo(11f, 6f); lineTo(11f, 10f); lineTo(6f, 10f); close()
            }
            stroke(1.6f) {
                moveTo(13f, 8.5f); lineTo(15.5f, 8.5f); lineTo(18f, 11f); lineTo(18f, 17.2f)
                arcTo(1.3f, 1.3f, 0f, false, false, 20.6f, 17.2f)
                lineTo(20.6f, 9f); lineTo(18f, 6.4f)
            }
            stroke(1.6f) { moveTo(2.8f, 20.6f); lineTo(14.2f, 20.6f) }
        }
    }

    val TripDoc: ImageVector by lazy {
        icon("TripDoc") {
            stroke(1.8f) {
                moveTo(6f, 3f); lineTo(14f, 3f); lineTo(18f, 7f); lineTo(18f, 21f); lineTo(6f, 21f); close()
                moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f)
            }
            stroke(1.6f) {
                moveTo(8.6f, 11f); lineTo(15.4f, 11f)
                moveTo(8.6f, 14f); lineTo(15.4f, 14f)
                moveTo(8.6f, 17f); lineTo(12.6f, 17f)
            }
        }
    }

    val Hazard: ImageVector by lazy {
        icon("Hazard") {
            stroke(2.2f) { moveTo(12f, 3f); lineTo(22f, 20f); lineTo(2f, 20f); close() }
            stroke(1.8f) { moveTo(12f, 9.2f); lineTo(16.2f, 16.6f); lineTo(7.8f, 16.6f); close() }
        }
    }

    val Power: ImageVector by lazy {
        icon("Power") {
            stroke(2.2f) {
                moveTo(7.05f, 6.9f)
                arcTo(7.5f, 7.5f, 0f, true, false, 16.95f, 6.9f)
            }
            stroke(2.2f) { moveTo(12f, 3f); lineTo(12f, 11.5f) }
        }
    }

    /** Detailed Info toggle: a 3×3 tile grid. */
    val DetailGrid: ImageVector by lazy {
        icon("DetailGrid") {
            fill {
                for (r in 0..2) for (c in 0..2) {
                    val x = 3.5f + c * 6f
                    val y = 3.5f + r * 6f
                    moveTo(x, y); lineTo(x + 5f, y); lineTo(x + 5f, y + 5f); lineTo(x, y + 5f); close()
                }
            }
        }
    }

    val all: List<ImageVector>
        get() = listOf(
            FuelDrop, OverspeedGauge, Coolant, Battery, EcoLeaf, AccelCar, BrakeCar, Idling, MilEngine, ObdPlug,
            Pin, Clock, FuelPump, TripDoc, Hazard, Power, DetailGrid,
        )
}

private val White = SolidColor(Color.White)

internal fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply(block)
        .build()

internal fun ImageVector.Builder.fill(type: PathFillType = PathFillType.NonZero, block: PathBuilder.() -> Unit) =
    path(fill = White, pathFillType = type, pathBuilder = block)

internal fun ImageVector.Builder.stroke(width: Float = 2f, block: PathBuilder.() -> Unit) =
    path(
        stroke = White, strokeLineWidth = width, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
    )

internal fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}

/** Side view of a hatchback on the full 24-unit grid (x 3–21), wheels at x 6 and 17. */
private fun ImageVector.Builder.car() {
    fill(PathFillType.EvenOdd) {
        moveTo(3f, 15f)
        lineTo(4.2f, 11.4f)
        curveTo(4.6f, 10.5f, 5.4f, 10f, 6.4f, 10f)
        lineTo(13f, 10f)
        curveTo(13.8f, 10f, 14.5f, 10.3f, 15f, 10.9f)
        lineTo(17.3f, 13.5f)
        lineTo(19.6f, 14f)
        curveTo(20.4f, 14.2f, 21f, 14.9f, 21f, 15.7f)
        lineTo(21f, 17f)
        lineTo(19.2f, 17f)
        arcTo(2.2f, 2.2f, 0f, false, false, 14.8f, 17f)
        lineTo(8.2f, 17f)
        arcTo(2.2f, 2.2f, 0f, false, false, 3.8f, 17f)
        lineTo(3f, 17f)
        close()
        // Window.
        moveTo(6.6f, 11.4f); lineTo(12.6f, 11.4f); lineTo(14.6f, 13.6f); lineTo(5.8f, 13.6f); close()
    }
    fill {
        circle(6f, 17f, 1.8f)
        circle(17f, 17f, 1.8f)
    }
}

/** Icon for a Detailed Info tile. */
val DetailIcon.vector: ImageVector
    get() = when (this) {
        DetailIcon.BATTERY -> DashboardIcons.Battery
        DetailIcon.COOLANT -> DashboardIcons.Coolant
        DetailIcon.MIL -> DashboardIcons.MilEngine
        DetailIcon.PIN -> DashboardIcons.Pin
        DetailIcon.OIL -> DashboardIcons.Coolant
        DetailIcon.OBD -> DashboardIcons.ObdPlug
        DetailIcon.ECO -> DashboardIcons.EcoLeaf
        DetailIcon.OVERSPEED -> DashboardIcons.OverspeedGauge
        DetailIcon.IDLING -> DashboardIcons.Idling
    }
