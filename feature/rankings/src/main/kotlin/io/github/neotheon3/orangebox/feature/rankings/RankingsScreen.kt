package io.github.neotheon3.orangebox.feature.rankings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.neotheon3.orangebox.core.designsystem.LocalUnits
import io.github.neotheon3.orangebox.core.network.MyRankingDto
import io.github.neotheon3.orangebox.core.network.PointsBalanceDto
import io.github.neotheon3.orangebox.core.network.PointsLedgerEntryDto
import io.github.neotheon3.orangebox.core.network.RankingEntryDto
import io.github.neotheon3.orangebox.core.network.RankingMetrics
import io.github.neotheon3.orangebox.core.network.ScoreStarsDto
import io.github.neotheon3.orangebox.core.network.StatsSummaryDto
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun RankingsRoute(onBack: () -> Unit, viewModel: RankingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RankingsScreen(
        state,
        RankingsActions(
            onBack = onBack,
            onTab = viewModel::selectTab,
            onRetry = viewModel::retry,
            onSummaryPeriod = viewModel::setSummaryPeriod,
            onMetric = viewModel::setMetric,
            onRankingPeriod = viewModel::setRankingPeriod,
            onLoadMoreRanking = viewModel::loadMoreRanking,
            onOptIn = viewModel::setRankingOptIn,
            onLoadMoreLedger = viewModel::loadMoreLedger,
        ),
    )
}

data class RankingsActions(
    val onBack: () -> Unit = {},
    val onTab: (MemberTab) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSummaryPeriod: (String) -> Unit = {},
    val onMetric: (String) -> Unit = {},
    val onRankingPeriod: (String) -> Unit = {},
    val onLoadMoreRanking: () -> Unit = {},
    val onOptIn: (Boolean) -> Unit = {},
    val onLoadMoreLedger: () -> Unit = {},
)

/** Server stats, rankings and points (#68): Material 3 defaults, like Settings and Trips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingsScreen(state: RankingsUiState, actions: RankingsActions, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.member_title)) },
                    navigationIcon = {
                        IconButton(onClick = actions.onBack, modifier = Modifier.testTag("member_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.member_back))
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                    MemberTab.entries.forEach { tab ->
                        Tab(
                            selected = state.tab == tab,
                            onClick = { actions.onTab(tab) },
                            text = {
                                Text(
                                    stringResource(
                                        when (tab) {
                                            MemberTab.SUMMARY -> R.string.member_tab_summary
                                            MemberTab.RANKINGS -> R.string.member_tab_rankings
                                            MemberTab.POINTS -> R.string.member_tab_points
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.testTag("member_tab_${tab.name.lowercase()}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("member_list"),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Always the first item (empty when not Fake): an item inserted above the first one later would leave the
            // list anchored below it, scrolled past the note.
            item(key = "header") {
                if (state.fake) {
                    Text(
                        stringResource(R.string.member_fake_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("member_fake_note"),
                    )
                }
            }
            if (!state.available) {
                item(key = "signed_out") { StateMessage(Load.SignedOut, actions.onRetry) }
                return@LazyColumn
            }
            when (state.tab) {
                MemberTab.SUMMARY -> summaryTab(state, actions, locale)
                MemberTab.RANKINGS -> rankingsTab(state, actions, locale)
                MemberTab.POINTS -> pointsTab(state, actions, locale, zone)
            }
        }
    }
}

// --- shared ---

@Composable
private fun PeriodPicker(periods: List<String>, selected: String, tagPrefix: String, onPick: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        periods.forEachIndexed { i, p ->
            SegmentedButton(
                selected = selected == p,
                onClick = { onPick(p) },
                shape = SegmentedButtonDefaults.itemShape(i, periods.size),
                modifier = Modifier.testTag("${tagPrefix}_$p"),
            ) { Text(stringResource(MemberFormat.period(p)), maxLines = 1) }
        }
    }
}

/** Loading, not available, signed out or failed (with Retry). */
@Composable
private fun StateMessage(load: Load<*>, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        when (load) {
            Load.Loading -> CircularProgressIndicator(Modifier.testTag("member_loading"))
            Load.Unavailable -> Message(R.string.member_unavailable_title, R.string.member_unavailable_body, "member_unavailable")
            Load.SignedOut -> Message(R.string.member_signed_out_title, R.string.member_signed_out_body, "member_signed_out")
            is Load.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag("member_failed")) {
                Text(
                    stringResource(
                        when (load.reason) {
                            Load.Reason.NETWORK -> R.string.member_failed_network
                            Load.Reason.BUSY -> R.string.member_failed_busy
                            Load.Reason.SERVER -> R.string.member_failed_server
                        },
                    ),
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).testTag("member_retry")) { Text(stringResource(R.string.member_retry)) }
            }
            is Load.Ready<*> -> Unit
        }
    }
}

@Composable
private fun Message(title: Int, body: Int, tag: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 420.dp).testTag(tag)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

// --- summary ---

private fun LazyListScope.summaryTab(state: RankingsUiState, actions: RankingsActions, locale: Locale) {
    item(key = "summary_period") { PeriodPicker(RankingsUiState.SUMMARY_PERIODS, state.summaryPeriod, "member_summary_period", actions.onSummaryPeriod) }
    when (val s = state.summary) {
        is Load.Ready -> {
            item(key = "summary_card") { SummaryCard(s.value, locale) }
            s.value.scoreStars?.let { stars -> item(key = "summary_stars") { StarsCard(stars) } }
        }
        else -> item(key = "summary_state") { StateMessage(s, actions.onRetry) }
    }
}

@Composable
private fun SummaryCard(s: StatsSummaryDto, locale: Locale) {
    val units = LocalUnits.current
    val dash = "—"
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    fun day(d: String) = runCatching { LocalDate.parse(d).format(date) }.getOrDefault(d)
    OutlinedCard(Modifier.fillMaxWidth().testTag("member_summary")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (s.from == s.to) day(s.from) else stringResource(R.string.member_summary_range, day(s.from), day(s.to)),
                style = MaterialTheme.typography.titleMedium,
            )
            if (s.tripCount == 0) {
                Text(stringResource(R.string.member_summary_empty), modifier = Modifier.testTag("member_summary_empty"))
                Text(stringResource(R.string.member_summary_local_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(Modifier.fillMaxWidth()) {
                Figure(stringResource(R.string.member_summary_trips), MemberFormat.integer(s.tripCount, locale), Modifier.weight(1f))
                Figure(
                    stringResource(R.string.member_summary_distance),
                    s.distanceKm?.let { MemberFormat.decimal(units.distance(it), locale, 1) + " " + units.distanceLabel } ?: dash,
                    Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Figure(stringResource(R.string.member_summary_time), s.durationS?.let(MemberFormat::elapsed) ?: dash, Modifier.weight(1f))
                Figure(
                    stringResource(R.string.member_summary_economy),
                    s.fuelEconomyKmPerL?.let(units::economy)?.let { MemberFormat.decimal(it, locale, 1) + " " + units.economyLabel } ?: dash,
                    Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Figure(stringResource(R.string.member_summary_score), s.drivingScore?.let { MemberFormat.decimal(it, locale, 0) } ?: dash, Modifier.weight(1f).testTag("member_summary_score"))
                Figure(stringResource(R.string.member_summary_level), s.level?.let { MemberFormat.integer(it, locale) } ?: dash, Modifier.weight(1f))
            }
            Figure(
                stringResource(R.string.member_summary_harsh),
                MemberFormat.integer((s.harshAccelCount ?: 0) + (s.harshBrakeCount ?: 0), locale),
            )
        }
    }
}

@Composable
private fun StarsCard(stars: ScoreStarsDto) {
    val rows = listOfNotNull(
        stars.harshDriving?.let { R.string.member_star_harsh to it },
        stars.idling?.let { R.string.member_star_idling to it },
        stars.normalSpeed?.let { R.string.member_star_normal to it },
        stars.ecoSpeed?.let { R.string.member_star_eco to it },
        stars.overSpeed?.let { R.string.member_star_over to it },
        stars.highLoad?.let { R.string.member_star_load to it },
        stars.fuelCut?.let { R.string.member_star_fuel_cut to it },
    )
    if (rows.isEmpty()) return
    OutlinedCard(Modifier.fillMaxWidth().testTag("member_stars")) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.member_summary_stars),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
            rows.forEach { (label, n) ->
                val count = n.coerceIn(0, 5)
                val description = stringResource(R.string.member_stars_description, count)
                ListItem(
                    headlineContent = { Text(stringResource(label)) },
                    trailingContent = {
                        Text(
                            "★".repeat(count) + "☆".repeat(5 - count),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}

// --- rankings ---

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.rankingsTab(state: RankingsUiState, actions: RankingsActions, locale: Locale) {
    item(key = "optin") { OptInCard(state, actions) }
    item(key = "metrics") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("member_metrics")) {
            RankingMetrics.ALL.forEach { m ->
                FilterChip(
                    selected = state.metric == m,
                    onClick = { actions.onMetric(m) },
                    label = { Text(stringResource(MemberFormat.metric(m))) },
                    modifier = Modifier.testTag("member_metric_$m"),
                )
            }
        }
    }
    item(key = "ranking_period") { PeriodPicker(RankingsUiState.RANKING_PERIODS, state.rankingPeriod, "member_ranking_period", actions.onRankingPeriod) }
    when (val b = state.board) {
        is Load.Ready -> {
            item(key = "me") { MyRankCard(state.me, state.metric, state.optedIn, locale) }
            if (b.value.entries.isEmpty()) {
                item(key = "board_empty") {
                    Text(stringResource(R.string.member_board_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("member_board_empty"))
                }
            }
            items(b.value.entries, key = { "row_${it.rank}_${it.displayName}_${it.isMe}" }) { e -> RankingRow(e, state.metric, locale) }
            if (b.value.nextCursor != null) {
                item(key = "board_more") {
                    LoadMore(b.value.loadingMore, "member_board_more", actions.onLoadMoreRanking)
                }
            }
        }
        else -> item(key = "board_state") { StateMessage(b, actions.onRetry) }
    }
}

@Composable
private fun OptInCard(state: RankingsUiState, actions: RankingsActions) {
    val profile = (state.profile as? Load.Ready)?.value
    OutlinedCard(Modifier.fillMaxWidth().testTag("member_optin_card")) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.member_optin_title)) },
            supportingContent = {
                Column {
                    Text(profile?.displayName?.let { stringResource(R.string.member_optin_body, it) } ?: stringResource(R.string.member_optin_body_unknown))
                    if (state.optInFailed) {
                        Text(stringResource(R.string.member_optin_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("member_optin_failed"))
                    }
                }
            },
            trailingContent = {
                Switch(
                    checked = state.optedIn,
                    onCheckedChange = actions.onOptIn,
                    enabled = profile != null && !state.optInSaving,
                    modifier = Modifier.testTag("member_optin"),
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }
}

@Composable
private fun metricText(metric: String, value: Double, locale: Locale): String {
    val (res, number) = MemberFormat.metricValue(metric, value, locale)
    return res?.let { stringResource(it, number) } ?: number
}

@Composable
private fun MyRankCard(me: Load<MyRankingDto?>, metric: String, optedIn: Boolean, locale: Locale) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("member_my_rank")) {
        Column(Modifier.padding(16.dp)) {
            when (me) {
                is Load.Ready -> {
                    val r = me.value
                    if (r == null) {
                        Text(stringResource(R.string.member_not_ranked), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (optedIn) R.string.member_not_ranked_hint_on else R.string.member_not_ranked_hint_off),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(stringResource(R.string.member_my_rank, r.rank, r.participants), style = MaterialTheme.typography.titleMedium)
                        Text(metricText(metric, r.value, locale), style = MaterialTheme.typography.headlineSmall)
                        r.percentile?.let {
                            Text(
                                stringResource(R.string.member_my_rank_percentile, MemberFormat.decimal(it, locale, 0) + "%"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Load.Loading -> CircularProgressIndicator()
                else -> Unit
            }
        }
    }
}

@Composable
private fun RankingRow(e: RankingEntryDto, metric: String, locale: Locale) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(Modifier.fillMaxWidth().testTag(if (e.isMe) "member_row_me" else "member_row_${e.rank}")) {
        ListItem(
            leadingContent = {
                Text(
                    stringResource(R.string.member_rank, e.rank),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = 40.dp),
                )
            },
            headlineContent = {
                Text(if (e.isMe) e.displayName + " · " + stringResource(R.string.member_you) else e.displayName, fontWeight = if (e.isMe) FontWeight.Bold else null)
            },
            supportingContent = e.vehicleLabel?.let { { Text(it) } },
            trailingContent = { Text(metricText(metric, e.value, locale), style = MaterialTheme.typography.titleSmall) },
            colors = ListItemDefaults.colors(containerColor = if (e.isMe) colors.secondaryContainer else colors.surface),
        )
    }
}

@Composable
private fun LoadMore(loading: Boolean, tag: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator() else OutlinedButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(stringResource(R.string.member_load_more)) }
    }
}

// --- points ---

private fun LazyListScope.pointsTab(state: RankingsUiState, actions: RankingsActions, locale: Locale, zone: ZoneId) {
    when (val p = state.points) {
        is Load.Ready -> item(key = "balance") { BalanceCard(p.value, locale, zone) }
        else -> {
            item(key = "points_state") { StateMessage(p, actions.onRetry) }
            return
        }
    }
    item(key = "history") {
        Text(stringResource(R.string.member_points_history), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
    when (val l = state.ledger) {
        is Load.Ready -> {
            if (l.value.entries.isEmpty()) {
                item(key = "ledger_empty") { Text(stringResource(R.string.member_points_empty), modifier = Modifier.testTag("member_ledger_empty")) }
            }
            items(l.value.entries, key = { it.id }) { LedgerRow(it, locale, zone) }
            if (l.value.nextCursor != null) item(key = "ledger_more") { LoadMore(l.value.loadingMore, "member_ledger_more", actions.onLoadMoreLedger) }
        }
        else -> item(key = "ledger_state") { StateMessage(l, actions.onRetry) }
    }
}

@Composable
private fun BalanceCard(b: PointsBalanceDto, locale: Locale, zone: ZoneId) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("member_balance")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Figure(stringResource(R.string.member_points_remaining), stringResource(R.string.member_value_points, MemberFormat.integer(b.remaining, locale)))
            Row(Modifier.fillMaxWidth()) {
                Figure(stringResource(R.string.member_points_earned), MemberFormat.integer(b.earned, locale), Modifier.weight(1f))
                Figure(stringResource(R.string.member_points_used), MemberFormat.integer(b.used, locale), Modifier.weight(1f))
            }
            b.expiringSoon?.takeIf { it > 0 }?.let { n ->
                val amount = MemberFormat.integer(n, locale)
                Text(
                    b.expiringAt?.let {
                        stringResource(R.string.member_points_expiring, amount, it.atZone(zone).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
                    } ?: stringResource(R.string.member_points_expiring_undated, amount),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            b.byCategory?.let { c ->
                val parts = listOfNotNull(
                    c.driving?.takeIf { it > 0 }?.let { R.string.member_category_driving to it },
                    c.level?.takeIf { it > 0 }?.let { R.string.member_category_level to it },
                    c.ranking?.takeIf { it > 0 }?.let { R.string.member_category_ranking to it },
                    c.event?.takeIf { it > 0 }?.let { R.string.member_category_event to it },
                )
                if (parts.isNotEmpty()) {
                    Text(stringResource(R.string.member_points_by_category), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(parts.map { (label, n) -> stringResource(label) + " " + MemberFormat.integer(n, locale) }.joinToString(" · "))
                }
            }
        }
    }
}

@Composable
private fun LedgerRow(e: PointsLedgerEntryDto, locale: Locale, zone: ZoneId) {
    val when_ = e.occurredAt.atZone(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
    ListItem(
        headlineContent = { Text(e.description?.takeIf { it.isNotBlank() } ?: stringResource(MemberFormat.category(e.category))) },
        supportingContent = { Text(stringResource(MemberFormat.category(e.category)) + " · " + when_) },
        trailingContent = {
            Text(
                (if (e.amount > 0) "+" else "") + MemberFormat.integer(e.amount, locale),
                color = if (e.amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
            )
        },
        modifier = Modifier.testTag("member_ledger_${e.id}"),
    )
}
