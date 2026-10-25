package io.github.neotheon3.orangebox.core.dashboardui

import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Glyphs for the ten diagnostics group lamps (#100), on the same 24×24 grid as [DashboardIcons].
 *
 * Provenance: drawn for Orange Box from basic shapes (lines, arcs, rounded rectangles), designed from
 * the group names only. No legacy `btn_dtc*` bitmap, or any other image, was copied, traced or used as
 * an underlay.
 */
object DtcGroupIcons {
    /** Air/Fuel: airflow lines feeding a fuel drop. */
    val AirFuel: ImageVector by lazy {
        icon("DtcAirFuel") {
            stroke(1.8f) {
                moveTo(2.5f, 7f); curveTo(5f, 5.5f, 7f, 8.5f, 9.5f, 7f)
                moveTo(2.5f, 11.5f); curveTo(5f, 10f, 7f, 13f, 9.5f, 11.5f)
                moveTo(2.5f, 16f); curveTo(5f, 14.5f, 7f, 17.5f, 9.5f, 16f)
            }
            fill {
                moveTo(16.5f, 4f)
                curveTo(14.6f, 6.8f, 12.2f, 9.6f, 12.2f, 13.2f)
                arcTo(4.3f, 4.3f, 0f, false, false, 20.8f, 13.2f)
                curveTo(20.8f, 9.6f, 18.4f, 6.8f, 16.5f, 4f)
                close()
            }
        }
    }

    /** Injection: an injector body with a spray fan. */
    val Injection: ImageVector by lazy {
        icon("DtcInjection") {
            fill {
                moveTo(9.5f, 2.5f); lineTo(14.5f, 2.5f); lineTo(14.5f, 5f); lineTo(13.5f, 5f); lineTo(13.5f, 11f)
                lineTo(12.8f, 13.5f); lineTo(11.2f, 13.5f); lineTo(10.5f, 11f); lineTo(10.5f, 5f); lineTo(9.5f, 5f); close()
            }
            stroke(1.6f) { moveTo(8.8f, 7.5f); lineTo(15.2f, 7.5f) }
            fill {
                circle(12f, 16.5f, 1f); circle(9.4f, 17.6f, 0.9f); circle(14.6f, 17.6f, 0.9f)
                circle(7.2f, 20f, 0.9f); circle(12f, 20.4f, 0.9f); circle(16.8f, 20f, 0.9f)
            }
        }
    }

    /** Ignition: a spark plug with a spark. */
    val Ignition: ImageVector by lazy {
        icon("DtcIgnition") {
            fill {
                moveTo(7.6f, 2.5f); lineTo(10.4f, 2.5f); lineTo(10.4f, 5f); lineTo(7.6f, 5f); close()
                moveTo(6.6f, 5.8f); lineTo(11.4f, 5.8f); lineTo(11.4f, 11f); lineTo(6.6f, 11f); close()
                moveTo(5.6f, 11.8f); lineTo(12.4f, 11.8f); lineTo(12.4f, 15f); lineTo(5.6f, 15f); close()
                moveTo(8.3f, 15.8f); lineTo(9.7f, 15.8f); lineTo(9.7f, 19f); lineTo(8.3f, 19f); close()
            }
            stroke(1.4f) { moveTo(9f, 20.5f); lineTo(11f, 20.5f) }
            fill {
                moveTo(17.5f, 7f); lineTo(14.2f, 13.4f); lineTo(16.8f, 13.4f); lineTo(15.4f, 19.5f)
                lineTo(20.2f, 11.6f); lineTo(17.4f, 11.6f); lineTo(19.4f, 7f); close()
            }
        }
    }

    /** Speed: a speedometer arc with ticks and the needle low. */
    val Speed: ImageVector by lazy {
        icon("DtcSpeed") {
            stroke(2f) {
                moveTo(4.2f, 17.5f)
                arcTo(8.5f, 8.5f, 0f, true, true, 19.8f, 17.5f)
            }
            stroke(1.4f) {
                moveTo(12f, 6.2f); lineTo(12f, 7.8f)
                moveTo(6.3f, 9.3f); lineTo(7.4f, 10.2f)
                moveTo(17.7f, 9.3f); lineTo(16.6f, 10.2f)
            }
            stroke(2f) { moveTo(12f, 15f); lineTo(8f, 11.6f) }
            fill { circle(12f, 15f, 1.7f) }
            stroke(1.6f) { moveTo(8f, 20.5f); lineTo(16f, 20.5f) }
        }
    }

    /** Circuits: a control-module chip with pins. */
    val Circuits: ImageVector by lazy {
        icon("DtcCircuits") {
            fill(PathFillType.EvenOdd) {
                moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close()
                moveTo(9f, 9f); lineTo(15f, 9f); lineTo(15f, 15f); lineTo(9f, 15f); close()
            }
            stroke(1.5f) {
                for (p in listOf(8.5f, 12f, 15.5f)) {
                    moveTo(p, 2.5f); lineTo(p, 6f); moveTo(p, 18f); lineTo(p, 21.5f)
                    moveTo(2.5f, p); lineTo(6f, p); moveTo(18f, p); lineTo(21.5f, p)
                }
            }
        }
    }

    /** Transmission: a gear-shift gate (H pattern) with knobs. */
    val Transmission: ImageVector by lazy {
        icon("DtcTransmission") {
            stroke(1.9f) {
                moveTo(5.5f, 6f); lineTo(5.5f, 18f)
                moveTo(12f, 6f); lineTo(12f, 18f)
                moveTo(18.5f, 6f); lineTo(18.5f, 12f)
                moveTo(5.5f, 12f); lineTo(18.5f, 12f)
            }
            fill {
                circle(5.5f, 4.6f, 2f); circle(12f, 4.6f, 2f); circle(18.5f, 4.6f, 2f)
                circle(5.5f, 19.4f, 2f); circle(12f, 19.4f, 2f)
            }
        }
    }

    /** Powertrain: a piston with its connecting rod and crank. */
    val Powertrain: ImageVector by lazy {
        icon("DtcPowertrain") {
            fill(PathFillType.EvenOdd) {
                moveTo(7f, 3f); lineTo(17f, 3f); lineTo(17f, 10.5f); lineTo(7f, 10.5f); close()
                moveTo(8.5f, 5f); lineTo(15.5f, 5f); lineTo(15.5f, 5.9f); lineTo(8.5f, 5.9f); close()
                moveTo(8.5f, 7f); lineTo(15.5f, 7f); lineTo(15.5f, 7.9f); lineTo(8.5f, 7.9f); close()
            }
            stroke(2.4f) { moveTo(12f, 10.5f); lineTo(14.5f, 17.5f) }
            stroke(1.8f) { circle(14.5f, 17.5f, 3.3f) }
            fill { circle(14.5f, 17.5f, 1.3f) }
        }
    }

    /** Body: the car seen from the front, with lamps. */
    val Body: ImageVector by lazy {
        icon("DtcBody") {
            fill(PathFillType.EvenOdd) {
                moveTo(7.2f, 5f); lineTo(16.8f, 5f); lineTo(18.8f, 10.5f); lineTo(20.5f, 11.2f)
                lineTo(20.5f, 17.5f); lineTo(3.5f, 17.5f); lineTo(3.5f, 11.2f); lineTo(5.2f, 10.5f); close()
                // Windscreen.
                moveTo(8.2f, 6.6f); lineTo(15.8f, 6.6f); lineTo(17.2f, 10.4f); lineTo(6.8f, 10.4f); close()
                // Lamps.
                moveTo(5f, 12.6f); lineTo(8.2f, 12.6f); lineTo(8.2f, 14.4f); lineTo(5f, 14.4f); close()
                moveTo(15.8f, 12.6f); lineTo(19f, 12.6f); lineTo(19f, 14.4f); lineTo(15.8f, 14.4f); close()
            }
            fill {
                moveTo(4.5f, 18.2f); lineTo(7.5f, 18.2f); lineTo(7.5f, 20.5f); lineTo(4.5f, 20.5f); close()
                moveTo(16.5f, 18.2f); lineTo(19.5f, 18.2f); lineTo(19.5f, 20.5f); lineTo(16.5f, 20.5f); close()
            }
        }
    }

    /** Chassis: a ladder frame with four wheels, from above. */
    val Chassis: ImageVector by lazy {
        icon("DtcChassis") {
            stroke(1.8f) {
                moveTo(9f, 5f); lineTo(9f, 19f)
                moveTo(15f, 5f); lineTo(15f, 19f)
                moveTo(9f, 7f); lineTo(15f, 7f)
                moveTo(9f, 12f); lineTo(15f, 12f)
                moveTo(9f, 17f); lineTo(15f, 17f)
                moveTo(6.5f, 7f); lineTo(9f, 7f); moveTo(15f, 7f); lineTo(17.5f, 7f)
                moveTo(6.5f, 17f); lineTo(9f, 17f); moveTo(15f, 17f); lineTo(17.5f, 17f)
            }
            fill {
                for (y in listOf(4.2f, 14.2f)) {
                    moveTo(3.5f, y); lineTo(6.5f, y); lineTo(6.5f, y + 5.6f); lineTo(3.5f, y + 5.6f); close()
                    moveTo(17.5f, y); lineTo(20.5f, y); lineTo(20.5f, y + 5.6f); lineTo(17.5f, y + 5.6f); close()
                }
            }
        }
    }

    /** Network: three modules linked by a bus. */
    val Network: ImageVector by lazy {
        icon("DtcNetwork") {
            stroke(1.8f) {
                moveTo(3f, 12f); lineTo(21f, 12f)
                moveTo(6.5f, 12f); lineTo(6.5f, 8f)
                moveTo(12f, 12f); lineTo(12f, 16f)
                moveTo(17.5f, 12f); lineTo(17.5f, 8f)
            }
            fill {
                moveTo(4f, 3.5f); lineTo(9f, 3.5f); lineTo(9f, 8f); lineTo(4f, 8f); close()
                moveTo(15f, 3.5f); lineTo(20f, 3.5f); lineTo(20f, 8f); lineTo(15f, 8f); close()
                moveTo(9.5f, 16f); lineTo(14.5f, 16f); lineTo(14.5f, 20.5f); lineTo(9.5f, 20.5f); close()
                circle(3f, 12f, 1.3f); circle(21f, 12f, 1.3f)
            }
        }
    }

    /** In legacy lamp order: Air/Fuel … Network. */
    val all: List<ImageVector>
        get() = listOf(AirFuel, Injection, Ignition, Speed, Circuits, Transmission, Powertrain, Body, Chassis, Network)
}
