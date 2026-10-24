package io.github.neotheon3.orangebox.feature.trips

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.database.TripRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

data class TripDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val detail: TripDetail? = null,
)

@HiltViewModel
class TripDetailViewModel internal constructor(
    private val tripId: Long,
    private val repository: TripRepository,
    private val zone: ZoneId,
) : ViewModel() {
    @Inject constructor(savedState: SavedStateHandle, repository: TripRepository) :
        this(savedState.get<Long>(TripDetailDestination.ARG_TRIP_ID) ?: -1L, repository, ZoneId.systemDefault())

    private val state = MutableStateFlow(TripDetailUiState())
    val uiState: StateFlow<TripDetailUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            // Follows the live trip list, so a trip still recording keeps updating its summary.
            repository.trips.map { list -> list.firstOrNull { it.id == tripId } }.collect { trip ->
                state.value = if (trip == null) {
                    TripDetailUiState(loading = false, notFound = true)
                } else {
                    TripDetailUiState(
                        loading = false,
                        detail = TripDetails.detail(trip, repository.samples(trip.id), repository.events(trip.id), zone),
                    )
                }
            }
        }
    }
}
