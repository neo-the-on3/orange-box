package io.github.neotheon3.orangebox.feature.trips.map

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** A WGS84 position. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** Normalised Web-Mercator coordinates: (0,0) is the top-left of the world map, (1,1) the bottom-right. */
data class MapPoint(val x: Double, val y: Double)

object WebMercator {
    /** Web Mercator is undefined at the poles; tiles stop at ±85.0511°. */
    const val MAX_LATITUDE = 85.05112878

    fun project(p: GeoPoint): MapPoint {
        val lat = p.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val x = (p.longitude + 180.0) / 360.0
        val rad = lat * PI / 180.0
        val y = (1.0 - ln(tan(PI / 4 + rad / 2)) / PI) / 2.0
        return MapPoint(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
    }

    /** Slippy-map tile (x, y) containing [p] at [zoom], as used in `{z}/{x}/{y}.png`. */
    fun tileOf(p: GeoPoint, zoom: Int): Pair<Int, Int> {
        val n = 1 shl zoom
        val m = project(p)
        return (m.x * n).toInt().coerceIn(0, n - 1) to (m.y * n).toInt().coerceIn(0, n - 1)
    }
}

/** Normalised bounds of a set of points. */
data class MapBounds(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    /** Grows a degenerate (single point, straight line) box so a fit-to-bounds zoom stays sensible. */
    fun withMinimumSize(min: Double): MapBounds {
        val cx = (left + right) / 2
        val cy = (top + bottom) / 2
        val hw = maxOf(width, min) / 2
        val hh = maxOf(height, min) / 2
        return MapBounds((cx - hw).coerceAtLeast(0.0), (cy - hh).coerceAtLeast(0.0), (cx + hw).coerceAtMost(1.0), (cy + hh).coerceAtMost(1.0))
    }

    companion object {
        fun of(points: List<MapPoint>): MapBounds? = if (points.isEmpty()) null else MapBounds(
            points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y },
        )
    }
}
