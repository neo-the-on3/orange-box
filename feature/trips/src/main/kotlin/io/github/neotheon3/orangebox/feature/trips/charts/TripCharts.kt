package io.github.neotheon3.orangebox.feature.trips.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.feature.trips.R
import io.github.neotheon3.orangebox.feature.trips.TripFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Speed-zone bars, then speed and economy over time (#45). Material 3 cards, colours from the theme. */
@Composable
fun TripCharts(
    zones: SpeedZones,
    speed: List<SeriesPoint>,
    economy: List<SeriesPoint>,
    locale: Locale,
    modifier: Modifier = Modifier,
    /** Shared time axis end, so both line charts line up; defaults to the last speed point. */
    durationS: Double = speed.lastOrNull()?.t ?: economy.lastOrNull()?.t ?: 0.0,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (zones.totalS > 0) SpeedZoneCard(zones, locale)
        if (speed.size >= 2) {
            LineChartCard(
                title = stringResource(R.string.chart_speed_title),
                unit = stringResource(R.string.chart_unit_kph),
                points = speed,
                color = MaterialTheme.colorScheme.primary,
                locale = locale,
                tag = "chart_speed",
                endS = durationS,
            )
        }
        if (economy.size >= 2) {
            LineChartCard(
                title = stringResource(R.string.chart_economy_title),
                unit = stringResource(R.string.chart_unit_kml),
                points = economy,
                color = MaterialTheme.colorScheme.tertiary,
                locale = locale,
                tag = "chart_economy",
                endS = durationS,
            )
        }
    }
}

@Composable
private fun SpeedZoneCard(zones: SpeedZones, locale: Locale) {
    val labels = zoneLabels(zones.bounds)
    val colors = zoneColors(zones.seconds.size)
    OutlinedCard(Modifier.fillMaxWidth().testTag("chart_speed_zones")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chart_speed_zones_title), style = MaterialTheme.typography.titleMedium)
            val shares = zones.shares
            zones.seconds.forEachIndexed { i, s ->
                val pct = (shares[i] * 100).roundToInt()
                val description = stringResource(R.string.chart_zone_description, labels[i], TripFormat.elapsed(s), pct)
                Row(
                    Modifier.fillMaxWidth().semantics { contentDescription = description }.testTag("chart_zone_$i"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(labels[i], style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
                    val track = MaterialTheme.colorScheme.surfaceVariant
                    Canvas(Modifier.weight(1f).height(14.dp)) {
                        val r = CornerRadius(size.height / 2, size.height / 2)
                        drawRoundRect(track, cornerRadius = r)
                        if (shares[i] > 0) drawRoundRect(colors[i], size = Size((size.width * shares[i].toFloat()).coerceAtLeast(size.height), size.height), cornerRadius = r)
                    }
                    Text(
                        String.format(locale, "%d%%", pct),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(44.dp).padding(start = 8.dp),
                    )
                    Text(TripFormat.elapsed(s), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun zoneLabels(bounds: List<Double>): List<String> {
    val b = bounds.map { it.roundToInt() }
    return buildList {
        add(stringResource(R.string.chart_zone_stopped))
        b.forEachIndexed { i, hi -> add(if (i == 0) "≤ $hi" else "${b[i - 1]}–$hi") }
        add("> ${b.last()}")
    }
}

/** Stopped in a neutral tone, then green → red as speed rises. */
@Composable
private fun zoneColors(n: Int): List<Color> {
    val ramp = listOf(Color(0xFF43A047), Color(0xFF7CB342), Color(0xFFFDD835), Color(0xFFFB8C00), Color(0xFFE53935))
    return listOf(MaterialTheme.colorScheme.outline) + List(n - 1) { ramp[(it * (ramp.size - 1) / (n - 2).coerceAtLeast(1)).coerceIn(0, ramp.size - 1)] }
}

@Composable
private fun LineChartCard(title: String, unit: String, points: List<SeriesPoint>, color: Color, locale: Locale, tag: String, endS: Double) {
    val maxV = niceMax(points.maxOf { it.value })
    val endT = maxOf(endS, points.last().t, 1.0)
    val description = stringResource(R.string.chart_line_description, title, TripFormat.decimal(points.maxOf { it.value }, locale), unit)
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.padding(top = 8.dp)) {
                Column(Modifier.height(140.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(axis(maxV, locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(axis(maxV / 2, locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.weight(1f)) {
                    val grid = MaterialTheme.colorScheme.outlineVariant
                    Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = description }) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                        listOf(0f, 0.5f, 1f).forEach { f ->
                            val y = size.height * f
                            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = if (f == 1f) null else dash)
                        }
                        val path = Path()
                        points.forEachIndexed { i, p ->
                            val x = (p.t / endT * size.width).toFloat()
                            val y = (size.height - p.value / maxV * size.height).toFloat()
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 28.dp, top = 4.dp)) {
                Text("0:00", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(TripFormat.elapsedShort(endT), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun axis(v: Double, locale: Locale) = if (v % 1.0 == 0.0) TripFormat.whole(v, locale) else TripFormat.decimal(v, locale)

/** A round axis maximum at or above [v]: 1, 2, 2.5 or 5 times a power of ten. */
internal fun niceMax(v: Double): Double {
    if (v <= 0) return 1.0
    var mag = 1.0
    while (mag * 10 <= v) mag *= 10
    while (mag > v) mag /= 10
    return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= v - 1e-9 }
}
