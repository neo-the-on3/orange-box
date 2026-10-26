package io.github.neotheon3.orangebox.feature.rankings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.datastore.MemberBackend
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.MyRankingDto
import io.github.neotheon3.orangebox.core.network.Periods
import io.github.neotheon3.orangebox.core.network.PointsBalanceDto
import io.github.neotheon3.orangebox.core.network.PointsLedgerEntryDto
import io.github.neotheon3.orangebox.core.network.ProfileDto
import io.github.neotheon3.orangebox.core.network.RankingEntryDto
import io.github.neotheon3.orangebox.core.network.RankingMetrics
import io.github.neotheon3.orangebox.core.network.StatsSummaryDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MemberTab { SUMMARY, RANKINGS, POINTS }

/** A leaderboard loaded so far: [nextCursor] non-null while there's more. */
data class Board(val entries: List<RankingEntryDto>, val nextCursor: String?, val loadingMore: Boolean = false)

data class Ledger(val entries: List<PointsLedgerEntryDto>, val nextCursor: String?, val loadingMore: Boolean = false)

data class RankingsUiState(
    /** Off, or Remote signed out: everything shows the sign-in state. */
    val available: Boolean = true,
    /** Fake mode: say the data is made up. */
    val fake: Boolean = false,
    val tab: MemberTab = MemberTab.SUMMARY,
    val summaryPeriod: String = Periods.WEEK,
    val summary: Load<StatsSummaryDto> = Load.Loading,
    val metric: String = RankingMetrics.DRIVING_SCORE,
    val rankingPeriod: String = Periods.MONTH,
    val board: Load<Board> = Load.Loading,
    val me: Load<MyRankingDto?> = Load.Loading,
    val profile: Load<ProfileDto> = Load.Loading,
    val optInSaving: Boolean = false,
    val optInFailed: Boolean = false,
    val points: Load<PointsBalanceDto> = Load.Loading,
    val ledger: Load<Ledger> = Load.Loading,
) {
    val optedIn: Boolean get() = (profile as? Load.Ready)?.value?.rankingOptIn == true

    companion object {
        val SUMMARY_PERIODS = listOf(Periods.DAY, Periods.WEEK, Periods.MONTH, Periods.YEAR)
        val RANKING_PERIODS = listOf(Periods.WEEK, Periods.MONTH, Periods.YEAR, Periods.ALL)
    }
}

/** Server stats, rankings and points (#68). Each tab loads when first shown; Retry reloads the current one. */
@HiltViewModel
class RankingsViewModel internal constructor(
    private val repository: MemberRepository,
    available: Flow<Boolean>,
    fake: Flow<Boolean>,
) : ViewModel() {
    @Inject constructor(repository: MemberRepository, backend: MemberBackend, config: BackendConfigRepository) :
        this(repository, backend.available, config.config.map { it.mode == BackendMode.FAKE })

    private val state = MutableStateFlow(RankingsUiState())
    val uiState: StateFlow<RankingsUiState> = state.asStateFlow()

    private val loaded = mutableSetOf<MemberTab>()
    private var boardJob: Job? = null

    init {
        viewModelScope.launch {
            available.collect { on ->
                val was = state.value.available
                state.update { it.copy(available = on) }
                if (on && !was) {
                    loaded.clear()
                    load(state.value.tab)
                }
            }
        }
        viewModelScope.launch { fake.collect { f -> state.update { it.copy(fake = f) } } }
        load(MemberTab.SUMMARY)
    }

    fun selectTab(tab: MemberTab) {
        state.update { it.copy(tab = tab) }
        if (tab !in loaded) load(tab)
    }

    fun retry() = load(state.value.tab)

    private fun load(tab: MemberTab) {
        loaded += tab
        when (tab) {
            MemberTab.SUMMARY -> loadSummary()
            MemberTab.RANKINGS -> {
                viewModelScope.launch {
                    state.update { it.copy(profile = Load.Loading, optInFailed = false) }
                    val p = repository.profile()
                    state.update { it.copy(profile = p) }
                }
                loadBoard()
            }
            MemberTab.POINTS -> viewModelScope.launch {
                state.update { it.copy(points = Load.Loading, ledger = Load.Loading) }
                val balance = repository.points()
                val ledger = repository.ledger().map { Ledger(it.items, it.nextCursor) }
                state.update { it.copy(points = balance, ledger = ledger) }
            }
        }
    }

    fun setSummaryPeriod(period: String) {
        if (period == state.value.summaryPeriod && state.value.summary !is Load.Failed) return
        state.update { it.copy(summaryPeriod = period) }
        loadSummary()
    }

    private var summaryJob: Job? = null

    private fun loadSummary() {
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            state.update { it.copy(summary = Load.Loading) }
            val period = state.value.summaryPeriod
            val result = repository.summary(period)
            state.update { if (it.summaryPeriod == period) it.copy(summary = result) else it }
        }
    }

    fun setMetric(metric: String) {
        if (metric == state.value.metric) return
        state.update { it.copy(metric = metric) }
        loadBoard()
    }

    fun setRankingPeriod(period: String) {
        if (period == state.value.rankingPeriod) return
        state.update { it.copy(rankingPeriod = period) }
        loadBoard()
    }

    private fun loadBoard() {
        boardJob?.cancel()
        boardJob = viewModelScope.launch {
            val (metric, period) = state.value.let { it.metric to it.rankingPeriod }
            state.update { it.copy(board = Load.Loading, me = Load.Loading) }
            val board = repository.ranking(metric, period).map { Board(it.items, it.nextCursor) }
            // The position is only asked for when the board exists (a missing feature is one state, not two).
            val me = if (board is Load.Ready) repository.myRanking(metric, period) else Load.Ready(null)
            state.update { it.copy(board = board, me = me) }
        }
    }

    fun loadMoreRanking() {
        val board = (state.value.board as? Load.Ready)?.value ?: return
        val cursor = board.nextCursor ?: return
        if (board.loadingMore) return
        val (metric, period) = state.value.let { it.metric to it.rankingPeriod }
        state.update { it.copy(board = Load.Ready(board.copy(loadingMore = true))) }
        boardJob = viewModelScope.launch {
            val more = repository.ranking(metric, period, cursor)
            state.update { s ->
                val now = (s.board as? Load.Ready)?.value ?: return@update s
                s.copy(
                    board = Load.Ready(
                        if (more is Load.Ready) Board(now.entries + more.value.items, more.value.nextCursor) else now.copy(loadingMore = false),
                    ),
                )
            }
        }
    }

    fun loadMoreLedger() {
        val ledger = (state.value.ledger as? Load.Ready)?.value ?: return
        val cursor = ledger.nextCursor ?: return
        if (ledger.loadingMore) return
        state.update { it.copy(ledger = Load.Ready(ledger.copy(loadingMore = true))) }
        viewModelScope.launch {
            val more = repository.ledger(cursor)
            state.update { s ->
                val now = (s.ledger as? Load.Ready)?.value ?: return@update s
                s.copy(ledger = Load.Ready(if (more is Load.Ready) Ledger(now.entries + more.value.items, more.value.nextCursor) else now.copy(loadingMore = false)))
            }
        }
    }

    /** `PATCH /v1/me {rankingOptIn}`; the board reloads so the member appears (or leaves). */
    fun setRankingOptIn(on: Boolean) {
        if (state.value.optInSaving) return
        state.update { it.copy(optInSaving = true, optInFailed = false) }
        viewModelScope.launch {
            when (val r = repository.setRankingOptIn(on)) {
                is Load.Ready -> {
                    state.update { it.copy(profile = r, optInSaving = false) }
                    loadBoard()
                }
                else -> state.update { it.copy(optInSaving = false, optInFailed = true) }
            }
        }
    }
}

internal inline fun <T, R> Load<T>.map(f: (T) -> R): Load<R> = when (this) {
    is Load.Ready -> Load.Ready(f(value))
    Load.Loading -> Load.Loading
    Load.Unavailable -> Load.Unavailable
    Load.SignedOut -> Load.SignedOut
    is Load.Failed -> this
}
