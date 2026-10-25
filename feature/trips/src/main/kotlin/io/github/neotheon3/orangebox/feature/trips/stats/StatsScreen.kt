package io.github.neotheon3.orangebox.feature.trips.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neotheon3.orangebox.feature.trips.R
import io.github.neotheon3.orangebox.feature.trips.TripFormat
import io.github.neotheon3.orangebox.feature.trips.displayUnits
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun StatsRoute(onBack: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatsScreen(state, onBack = onBack, onPeriod = viewModel::setPeriod)
}

/** Weekly or monthly totals of the recorded trips (#46), Material 3 defaults. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(state: StatsUiState, onBack: () -> Unit = {}, onPeriod: (StatsPeriod) -> Unit = {}, locale: Locale = Locale.getDefault()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("stats_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.trips_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("stats_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "period") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    StatsPeriod.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = state.period == p,
                            onClick = { onPeriod(p) },
                            shape = SegmentedButtonDefaults.itemShape(i, StatsPeriod.entries.size),
                            modifier = Modifier.testTag("stats_period_${p.name.lowercase()}"),
                        ) { Text(stringResource(if (p == StatsPeriod.WEEK) R.string.stats_weekly else R.string.stats_monthly)) }
                    }
                }
            }
            val current = state.current
            when {
                state.loading -> item(key = "loading") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                current == null -> Unit
                else -> {
                    item(key = "current") { CurrentCard(current, state.period, locale) }
                    item(key = "chart") { DistanceChart(state.periods, state.period, locale) }
                    item(key = "history_header") {
                        Text(stringResource(R.string.stats_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                    items(state.periods.drop(1), key = { it.range.start.toString() }) { p -> PeriodRow(p, state.period, locale) }
                }
            }
        }
    }
}

@Composable
private fun CurrentCard(p: PeriodStats, period: StatsPeriod, locale: Locale) {
    val t = p.totals
    val dash = "—"
    OutlinedCard(Modifier.fillMaxWidth().testTag("stats_current")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(
                    stringResource(if (period == StatsPeriod.WEEK) R.string.stats_this_week else R.string.stats_this_month),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${periodLabel(p.range, period, locale)} · ${pluralStringResource(R.plurals.trips_count, t.trips, t.trips)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (t.trips == 0) {
                Text(stringResource(R.string.stats_no_trips), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("stats_empty"))
            } else {
                val tiles = listOf(
                    stringResource(R.string.metric_distance) to stringResource(R.string.trips_distance_km, TripFormat.km(displayUnits.distance(t.distanceKm), locale), displayUnits.distanceLabel),
                    stringResource(R.string.stats_driving_time) to TripFormat.elapsed(t.durationS),
                    stringResource(R.string.metric_economy) to (p.economyKmPerL?.let(displayUnits::economy)?.let { stringResource(R.string.trips_economy_kml, TripFormat.decimal(it, locale), displayUnits.economyLabel) } ?: dash),
                    stringResource(R.string.metric_fuel) to stringResource(R.string.unit_litres, TripFormat.decimal(t.fuelLitres, locale)),
                    stringResource(R.string.metric_eco_time) to (p.ecoShare?.let { "${(it * 100).roundToInt()}%" } ?: dash),
                    stringResource(R.string.metric_harsh) to stringResource(R.string.metric_harsh_value, t.harshAccel, t.harshBrake),
                    stringResource(R.string.stats_overspeed_events) to t.overspeed.toString(),
                    stringResource(R.string.metric_max_speed) to stringResource(R.string.unit_kph, TripFormat.decimal(displayUnits.speed(t.maxSpeedKph), locale), displayUnits.speedLabel),
                )
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (label, value) ->
                            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}.testTag("stats_tile_$label")) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Distance per period, oldest on the left, the current one highlighted. */
@Composable
private fun DistanceChart(periods: List<PeriodStats>, period: StatsPeriod, locale: Locale) {
    val ordered = periods.reversed()
    val max = ordered.maxOf { it.totals.distanceKm }.coerceAtLeast(1.0)
    val description = stringResource(R.string.stats_chart_description, ordered.size)
    OutlinedCard(Modifier.fillMaxWidth().testTag("stats_chart")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.stats_distance_chart), style = MaterialTheme.typography.titleMedium)
            val bar = MaterialTheme.colorScheme.primary
            val past = MaterialTheme.colorScheme.primaryContainer
            val base = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 12.dp).semantics { contentDescription = description }) {
                val slot = size.width / ordered.size
                val w = slot * 0.6f
                ordered.forEachIndexed { i, p ->
                    val h = (p.totals.distanceKm / max * size.height).toFloat()
                    if (h > 0f) {
                        drawRoundRect(
                            if (i == ordered.lastIndex) bar else past,
                            topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                        )
                    }
                }
                drawLine(base, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                ordered.forEach { p ->
                    Text(
                        shortLabel(p.range, period, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodRow(p: PeriodStats, period: StatsPeriod, locale: Locale) {
    val t = p.totals
    ListItem(
        headlineContent = { Text(periodLabel(p.range, period, locale)) },
        supportingContent = {
            Text(
                if (t.trips == 0) stringResource(R.string.trips_none_short) else listOfNotNull(
                    pluralStringResource(R.plurals.trips_count, t.trips, t.trips),
                    stringResource(R.string.trips_distance_km, TripFormat.km(displayUnits.distance(t.distanceKm), locale), displayUnits.distanceLabel),
                    TripFormat.elapsed(t.durationS),
                    p.economyKmPerL?.let(displayUnits::economy)?.let { stringResource(R.string.trips_economy_kml, TripFormat.decimal(it, locale), displayUnits.economyLabel) },
                ).joinToString(" · "),
            )
        },
        trailingContent = {
            if (t.harshEvents > 0) {
                Text(
                    pluralStringResource(R.plurals.stats_harsh, t.harshEvents, t.harshEvents),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        modifier = Modifier.testTag("stats_period_row_${p.range.start}"),
    )
}

internal fun periodLabel(r: PeriodRange, period: StatsPeriod, locale: Locale): String = when (period) {
    StatsPeriod.WEEK -> {
        val f = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        "${r.start.format(f)} – ${r.end.minusDays(1).format(f)}"
    }
    StatsPeriod.MONTH -> r.start.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }
}

private fun shortLabel(r: PeriodRange, period: StatsPeriod, locale: Locale): String = when (period) {
    StatsPeriod.WEEK -> r.start.format(DateTimeFormatter.ofPattern("d/M", locale))
    StatsPeriod.MONTH -> r.start.format(DateTimeFormatter.ofPattern("LLL", locale))
}
