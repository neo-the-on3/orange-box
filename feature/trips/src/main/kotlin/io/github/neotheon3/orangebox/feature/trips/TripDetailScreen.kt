package io.github.neotheon3.orangebox.feature.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun TripDetailRoute(onBack: () -> Unit, viewModel: TripDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TripDetailScreen(state, onBack = onBack)
}

/** Trip detail (Material 3): summary metrics and events. The route map (#44) and charts (#45) slot in above/below the metrics. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    onBack: () -> Unit = {},
    locale: Locale = Locale.getDefault(),
    charts: @Composable (TripDetail) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.trip_detail_title))
                        state.detail?.let {
                            Text(
                                it.header.start.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("trip_detail_subtitle"),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("trip_detail_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.trips_back))
                    }
                },
            )
        },
    ) { padding ->
        val detail = state.detail
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            detail == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.trip_detail_not_found), modifier = Modifier.testTag("trip_detail_not_found"))
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("trip_detail_list"),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "metrics") { MetricsGrid(detail.metrics, locale) }
                item(key = "gps") {
                    Text(
                        stringResource(R.string.trip_detail_gps_points, detail.route.size, detail.speedSeries.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp).testTag("trip_detail_gps_points"),
                    )
                }
                item(key = "charts") { charts(detail) }
                item(key = "events_header") {
                    Text(stringResource(R.string.trip_detail_events), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                }
                if (detail.events.isEmpty()) {
                    item(key = "no_events") {
                        Text(
                            stringResource(R.string.trip_detail_no_events),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("trip_detail_no_events"),
                        )
                    }
                }
                items(detail.events.withIndex().toList(), key = { "event_${it.index}" }) { (i, e) -> EventRow(i, e, locale) }
                // Fuel cut saves fuel: its own section, not counted as an event (#165).
                if (detail.fuelCuts.isNotEmpty()) {
                    item(key = "fuel_cut_header") {
                        Column(Modifier.padding(top = 4.dp)) {
                            Text(stringResource(R.string.trip_detail_fuel_cuts), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.trip_detail_fuel_cuts_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(detail.fuelCuts.withIndex().toList(), key = { "fuel_cut_${it.index}" }) { (i, e) -> EventRow(i, e, locale, tag = "trip_fuel_cut_$i") }
                }
            }
        }
    }
}

@Composable
private fun MetricsGrid(m: TripMetrics, locale: Locale) {
    val dash = "—"
    val tiles = listOf(
        stringResource(R.string.metric_distance) to stringResource(R.string.trips_distance_km, TripFormat.km(m.distanceKm, locale)),
        stringResource(R.string.metric_duration) to TripFormat.elapsed(m.durationS),
        stringResource(R.string.metric_avg_speed) to (m.avgSpeedKph?.let { stringResource(R.string.unit_kph, TripFormat.decimal(it, locale)) } ?: dash),
        stringResource(R.string.metric_max_speed) to stringResource(R.string.unit_kph, TripFormat.decimal(m.maxSpeedKph, locale)),
        stringResource(R.string.metric_fuel) to stringResource(R.string.unit_litres, String.format(locale, "%.2f", m.fuelLitres)),
        stringResource(R.string.metric_economy) to (m.economyKmPerL?.let { stringResource(R.string.trips_economy_kml, TripFormat.decimal(it, locale)) } ?: dash),
        stringResource(R.string.metric_co2) to stringResource(R.string.unit_kg, String.format(locale, "%.2f", m.co2Kg)),
        stringResource(R.string.metric_eco_time) to TripFormat.elapsed(m.ecoS),
        stringResource(R.string.metric_idle_time) to TripFormat.elapsed(m.idleS),
        stringResource(R.string.metric_overspeed_time) to TripFormat.elapsed(m.overspeedS),
        stringResource(R.string.metric_fuel_cut_time) to TripFormat.elapsed(m.fuelCutS),
        stringResource(R.string.metric_harsh) to stringResource(R.string.metric_harsh_value, m.harshAccel, m.harshBrake),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("trip_detail_metrics")) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    OutlinedCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(index: Int, e: TripEventItem, locale: Locale, tag: String = "trip_event_$index") {
    val time = e.time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale))
    // The peak while the event ran (#164), then how long it lasted.
    val value = e.value?.let {
        when (e.type) {
            TripEventType.HARSH_ACCEL, TripEventType.HARSH_BRAKE ->
                stringResource(R.string.event_peak, stringResource(R.string.unit_mps2, TripFormat.decimal(kotlin.math.abs(it), locale)))
            TripEventType.OVERSPEED -> stringResource(R.string.event_peak, stringResource(R.string.unit_kph, TripFormat.decimal(it, locale)))
            TripEventType.FUEL_CUT -> null
        }
    }
    val duration = e.durationS?.let { stringResource(R.string.unit_seconds, TripFormat.decimal(it, locale)) }
    ListItem(
        leadingContent = {
            Box(Modifier.size(28.dp).background(eventColor(e.type), CircleShape), contentAlignment = Alignment.Center) {
                Text(eventLetter(e.type), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        },
        headlineContent = { Text(eventLabel(e.type)) },
        supportingContent = { Text(listOfNotNull(time, value, duration).joinToString(" · ")) },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
internal fun eventLabel(type: TripEventType): String = stringResource(
    when (type) {
        TripEventType.HARSH_ACCEL -> R.string.event_harsh_accel
        TripEventType.HARSH_BRAKE -> R.string.event_harsh_brake
        TripEventType.OVERSPEED -> R.string.event_overspeed
        TripEventType.FUEL_CUT -> R.string.event_fuel_cut
    },
)

internal fun eventLetter(type: TripEventType) = when (type) {
    TripEventType.HARSH_ACCEL -> "A"
    TripEventType.HARSH_BRAKE -> "B"
    TripEventType.OVERSPEED -> "S"
    TripEventType.FUEL_CUT -> "F"
}

internal fun eventColor(type: TripEventType) = when (type) {
    TripEventType.HARSH_ACCEL -> Color(0xFFEF6C00)
    TripEventType.HARSH_BRAKE -> Color(0xFFC62828)
    TripEventType.OVERSPEED -> Color(0xFF6A1B9A)
    TripEventType.FUEL_CUT -> Color(0xFF00838F)
}
