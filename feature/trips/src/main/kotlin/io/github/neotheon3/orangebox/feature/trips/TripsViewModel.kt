package io.github.neotheon3.orangebox.feature.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.database.TripReassignment
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.datastore.BackendAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TripsViewModel internal constructor(
    repository: TripRepository,
    private val clock: Clock,
    private val locale: Locale,
    reassignment: TripReassignment = TripMover.NONE,
    account: BackendAccount? = null,
) : ViewModel() {
    @Inject constructor(repository: TripRepository, reassignment: TripReassignment, account: BackendAccount) :
        this(repository, Clock.systemDefaultZone(), Locale.getDefault(), reassignment, account)

    private val mover = TripMover(reassignment, account, viewModelScope)

    private val today: LocalDate get() = LocalDate.now(clock)
    private val month = MutableStateFlow(YearMonth.from(today))
    private val selectedDay = MutableStateFlow<LocalDate?>(null)

    val uiState: StateFlow<TripsUiState> = combine(repository.trips, month, selectedDay, mover.orphaned, mover.dialog) { trips, m, day, orphaned, dialog ->
        TripsCalendar.state(trips, m, day, clock.zone, today, WeekFields.of(locale).firstDayOfWeek)
            .copy(orphanedTripIds = orphaned, moveDialog = dialog)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TripsCalendar.state(emptyList(), month.value, null, clock.zone, today, WeekFields.of(locale).firstDayOfWeek).copy(loading = true),
    )

    fun previousMonth() = showMonth(month.value.minusMonths(1))
    fun nextMonth() = showMonth(month.value.plusMonths(1))

    fun showMonth(target: YearMonth) {
        month.value = target
        selectedDay.value = null
    }

    /** #203: move every trip of a vehicle deleted elsewhere (not just the visible month's). */
    fun startMoveOrphaned() = mover.start(uiState.value.orphanedTripIds)
    fun selectMoveTarget(vehicleId: Long) = mover.select(vehicleId)
    fun confirmMove() = mover.confirm()
    fun dismissMove() = mover.dismiss()

    /** Tapping the selected day again clears the filter. */
    fun onDayClicked(day: LocalDate) {
        if (YearMonth.from(day) != month.value) showMonth(YearMonth.from(day))
        selectedDay.update { if (it == day) null else day }
    }
}
