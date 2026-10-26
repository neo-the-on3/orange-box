package io.github.neotheon3.orangebox.feature.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun TripsRoute(onBack: () -> Unit, onOpenTrip: (Long) -> Unit, onOpenStats: () -> Unit = {}, viewModel: TripsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TripsScreen(
        state = state,
        actions = TripsActions(
            onBack = onBack,
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onDayClicked = viewModel::onDayClicked,
            onOpenTrip = onOpenTrip,
            onOpenStats = onOpenStats,
            move = MoveTripsActions(viewModel::startMoveOrphaned, viewModel::selectMoveTarget, viewModel::confirmMove, viewModel::dismissMove),
        ),
    )
}

data class TripsActions(
    val onBack: () -> Unit = {},
    val onPreviousMonth: () -> Unit = {},
    val onNextMonth: () -> Unit = {},
    val onDayClicked: (LocalDate) -> Unit = {},
    val onOpenTrip: (Long) -> Unit = {},
    val onOpenStats: () -> Unit = {},
    val move: MoveTripsActions = MoveTripsActions(),
)

/** Trip history: a month calendar marking days with trips, and the trips of the month or the selected day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(state: TripsUiState, actions: TripsActions, locale: Locale = Locale.getDefault()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trips_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack, modifier = Modifier.testTag("trips_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.trips_back))
                    }
                },
                actions = {
                    TextButton(onClick = actions.onOpenStats, modifier = Modifier.testTag("trips_stats")) { Text(stringResource(R.string.trips_stats)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("trips_list"),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "calendar") {
                // The #203 card lives inside the first item: an item inserted above it would be scrolled out of view.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.orphanedTripIds.isNotEmpty()) {
                        OrphanedTripsCard(state.orphanedTripIds.size, single = false, onMove = actions.move.onStart)
                    }
                    MonthCalendar(state, actions, locale)
                }
            }
            item(key = "summary") { ListHeader(state, locale) }
            if (state.isEmptyMonth) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.trips_empty_month, monthTitle(state.month, locale)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp).testTag("trips_empty"),
                    )
                }
            }
            state.tripsByDay.forEach { (day, trips) ->
                if (state.selectedDay == null) {
                    item(key = "day_$day") {
                        Text(
                            day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp).testTag("trips_day_header_$day"),
                        )
                    }
                }
                items(trips, key = { it.id }) { trip ->
                    TripRow(trip, locale, orphaned = trip.id in state.orphanedTripIds) { actions.onOpenTrip(trip.id) }
                }
            }
        }
    }
    state.moveDialog?.let { MoveTripsDialogView(it, actions.move) }
}

@Composable
private fun MonthCalendar(state: TripsUiState, actions: TripsActions, locale: Locale) {
    OutlinedCard(Modifier.fillMaxWidth().widthIn(max = 560.dp).testTag("trips_calendar")) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onPreviousMonth, modifier = Modifier.testTag("trips_prev_month")) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.trips_previous_month))
                }
                Text(
                    monthTitle(state.month, locale),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).testTag("trips_month_title"),
                )
                IconButton(onClick = actions.onNextMonth, modifier = Modifier.testTag("trips_next_month")) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.trips_next_month))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { i ->
                    Text(
                        state.firstDayOfWeek.plus(i.toLong()).getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    )
                }
            }
            state.weeks.forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        DayCell(day, selected = day.date == state.selectedDay, locale, Modifier.weight(1f)) { actions.onDayClicked(day.date) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: CalendarDay, selected: Boolean, locale: Locale, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tripsLabel = if (day.tripCount > 0) pluralStringResource(R.plurals.trips_count, day.tripCount, day.tripCount) else stringResource(R.string.trips_none_short)
    val description = day.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)) + ", " + tripsLabel
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .then(if (day.inMonth) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = description; this.selected = selected }
            .testTag("trips_day_${day.date}"),
        contentAlignment = Alignment.Center,
    ) {
        val container = when {
            selected -> colors.primary
            day.tripCount > 0 && day.inMonth -> colors.secondaryContainer
            else -> androidx.compose.ui.graphics.Color.Transparent
        }
        val content = when {
            selected -> colors.onPrimary
            !day.inMonth -> colors.onSurface.copy(alpha = 0.38f)
            day.tripCount > 0 -> colors.onSecondaryContainer
            else -> colors.onSurface
        }
        Surface(
            shape = CircleShape,
            color = container,
            border = if (day.isToday && !selected) androidx.compose.foundation.BorderStroke(1.dp, colors.primary) else null,
            modifier = Modifier.size(40.dp),
        ) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(
                    day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (day.tripCount > 0 && day.inMonth) FontWeight.Bold else FontWeight.Normal,
                    color = content,
                )
                if (day.tripCount > 0 && day.inMonth) {
                    // Dot under the number: the trip marker does not rely on colour alone.
                    Box(Modifier.size(4.dp).background(content, CircleShape).testTag("trips_day_marker"))
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ListHeader(state: TripsUiState, locale: Locale) {
    val title = state.selectedDay?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
        ?: monthTitle(state.month, locale)
    Column(Modifier.padding(top = 8.dp, start = 4.dp).testTag("trips_summary")) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (state.summary.trips > 0) {
            Text(
                stringResource(
                    R.string.trips_month_summary,
                    pluralStringResource(R.plurals.trips_count, state.summary.trips, state.summary.trips),
                    TripFormat.km(displayUnits.distance(state.summary.distanceKm), locale),
                    TripFormat.elapsed(state.summary.durationS),
                    displayUnits.distanceLabel,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TripRow(trip: TripListItem, locale: Locale, orphaned: Boolean = false, onClick: () -> Unit) {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val day = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    val period = trip.start.format(day) + ", " + trip.start.format(time) + (trip.end?.let { " – " + it.format(time) } ?: "")
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("trip_row_${trip.id}")) {
        ListItem(
            overlineContent = if (orphaned) {
                {
                    Text(
                        stringResource(R.string.trips_vehicle_deleted_elsewhere),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("trip_orphaned_${trip.id}"),
                    )
                }
            } else {
                null
            },
            headlineContent = { Text(period) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        stringResource(R.string.trips_distance_km, TripFormat.km(displayUnits.distance(trip.distanceKm), locale), displayUnits.distanceLabel),
                        TripFormat.elapsed(trip.durationS),
                        trip.economyKmPerL?.let(displayUnits::economy)?.let { stringResource(R.string.trips_economy_kml, TripFormat.decimal(it, locale), displayUnits.economyLabel) },
                        stringResource(R.string.trips_eco_time, TripFormat.elapsed(trip.ecoS)),
                    ).joinToString(" · "),
                )
            },
            trailingContent = {
                when {
                    trip.recording -> AssistChip(onClick = onClick, label = { Text(stringResource(R.string.trips_recording)) })
                    trip.harshEvents + trip.overspeedEvents > 0 -> Text(
                        pluralStringResource(R.plurals.trips_events, trip.harshEvents + trip.overspeedEvents, trip.harshEvents + trip.overspeedEvents),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}

fun monthTitle(month: YearMonth, locale: Locale): String =
    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }
