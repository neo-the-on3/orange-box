package io.github.neotheon3.orangebox.feature.trips.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.datastore.MemberBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

data class StatsUiState(
    val loading: Boolean = true,
    val period: StatsPeriod = StatsPeriod.WEEK,
    /** Newest first; the first is the current week or month. */
    val periods: List<PeriodStats> = emptyList(),
    /** Fake, or Remote signed in: the server's rankings and points can be opened (#68). */
    val rankingsAvailable: Boolean = false,
) {
    val current: PeriodStats? get() = periods.firstOrNull()
}

@HiltViewModel
class StatsViewModel internal constructor(
    private val repository: TripRepository,
    private val clock: Clock,
    private val locale: Locale,
    rankingsAvailable: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    @Inject constructor(repository: TripRepository, members: MemberBackend) :
        this(repository, Clock.systemDefaultZone(), Locale.getDefault(), members.available)

    private val period = MutableStateFlow(StatsPeriod.WEEK)

    /** Re-queried whenever the trip list changes (a trip ends) or the period switches. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StatsUiState> = combine(repository.trips, period) { _, p -> p }
        .mapLatest { p ->
            val zone = clock.zone
            val ranges = TripStats.ranges(p, LocalDate.now(clock), COUNT, WeekFields.of(locale).firstDayOfWeek)
            StatsUiState(loading = false, period = p, periods = ranges.map { r -> PeriodStats(r, repository.totals(r.fromMillis(zone), r.toMillis(zone))) })
        }
        .combine(rankingsAvailable.distinctUntilChanged()) { s, on -> s.copy(rankingsAvailable = on) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun setPeriod(p: StatsPeriod) { period.value = p }

    companion object {
        /** Periods shown: 8 weeks or 8 months. */
        const val COUNT = 8
    }
}
