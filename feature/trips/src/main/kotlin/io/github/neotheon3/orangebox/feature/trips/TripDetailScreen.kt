package io.github.neotheon3.orangebox.feature.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neotheon3.orangebox.feature.trips.map.MapTiles
import io.github.neotheon3.orangebox.feature.trips.map.RouteMap
import io.github.neotheon3.orangebox.feature.trips.map.RouteMarker
import ovh.plrapps.mapcompose.core.TileStreamProvider
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun TripDetailRoute(onBack: () -> Unit, viewModel: TripDetailViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tiles = remember(state.tilesEnabled) {
        if (state.tilesEnabled) MapTiles.Provider(MapTiles.sharedClient(context)) else null
    }
    TripDetailScreen(state, tiles, onBack = onBack, onTilesChanged = viewModel::setTilesEnabled)
}

/** Trip detail (Material 3): route map, summary metrics, events. Speed-zone and metric charts slot in below the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    state: TripDetailUiState,
    tiles: TileStreamProvider?,
    onBack: () -> Unit = {},
    onTilesChanged: (Boolean) -> Unit = {},
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
                actions = { MapMenu(state.tilesEnabled, onTilesChanged) },
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
                item(key = "map") { MapCard(detail, if (state.tilesEnabled) tiles else null) }
                item(key = "metrics") { MetricsGrid(detail.metrics, locale) }
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
            }
        }
    }
}

@Composable
private fun MapMenu(tilesEnabled: Boolean, onTilesChanged: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("trip_detail_more")) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.trip_detail_more))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.trip_detail_map_tiles)) },
            trailingIcon = { Checkbox(checked = tilesEnabled, onCheckedChange = null) },
            onClick = { open = false; onTilesChanged(!tilesEnabled) },
            modifier = Modifier.testTag("trip_detail_tiles_toggle"),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.trip_detail_report_map_issue)) },
            onClick = { open = false; runCatching { uri.openUri(MapTiles.FIX_THE_MAP_URL) } },
        )
    }
}

@Composable
private fun MapCard(detail: TripDetail, tiles: TileStreamProvider?) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        if (detail.hasRoute) {
            val markers = detail.events.withIndex().mapNotNull { (i, e) ->
                e.position?.let { RouteMarker("event_$i", it, eventLetter(e.type), eventColor(e.type), eventLabel(e.type)) }
            }
            RouteMap(
                route = detail.route,
                markers = markers,
                tiles = tiles,
                routeDescription = stringResource(R.string.trip_detail_map_description, detail.route.size),
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        } else {
            ListItem(
                leadingContent = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.trip_detail_no_location)) },
                supportingContent = { Text(stringResource(R.string.trip_detail_no_location_hint)) },
                modifier = Modifier.testTag("trip_detail_no_location"),
            )
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
private fun EventRow(index: Int, e: TripEventItem, locale: Locale) {
    val time = e.time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale))
    val value = e.value?.let {
        when (e.type) {
            TripEventType.HARSH_ACCEL, TripEventType.HARSH_BRAKE -> stringResource(R.string.unit_mps2, TripFormat.decimal(kotlin.math.abs(it), locale))
            TripEventType.OVERSPEED -> stringResource(R.string.unit_kph, TripFormat.decimal(it, locale))
            TripEventType.FUEL_CUT -> null
        }
    }
    ListItem(
        leadingContent = {
            Box(Modifier.size(28.dp).background(eventColor(e.type), CircleShape), contentAlignment = Alignment.Center) {
                Text(eventLetter(e.type), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        },
        headlineContent = { Text(eventLabel(e.type)) },
        supportingContent = { Text(listOfNotNull(time, value).joinToString(" · ")) },
        modifier = Modifier.testTag("trip_event_$index"),
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
