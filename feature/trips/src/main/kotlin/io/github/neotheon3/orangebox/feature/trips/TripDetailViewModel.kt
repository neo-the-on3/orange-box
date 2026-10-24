package io.github.neotheon3.orangebox.feature.trips

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

data class TripDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val detail: TripDetail? = null,
    val tilesEnabled: Boolean = true,
)

@HiltViewModel
class TripDetailViewModel internal constructor(
    private val tripId: Long,
    private val repository: TripRepository,
    private val prefs: AppPreferencesRepository,
    private val zone: ZoneId,
) : ViewModel() {
    @Inject constructor(savedState: SavedStateHandle, repository: TripRepository, prefs: AppPreferencesRepository) :
        this(savedState.get<Long>(TripDetailDestination.ARG_TRIP_ID) ?: -1L, repository, prefs, ZoneId.systemDefault())

    private val loaded = MutableStateFlow<TripDetailUiState>(TripDetailUiState())

    val uiState: StateFlow<TripDetailUiState> = combine(loaded, prefs.preferences.map { it.mapTilesEnabled }) { s, tiles ->
        s.copy(tilesEnabled = tiles)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripDetailUiState())

    init {
        viewModelScope.launch {
            // The trip row is re-read from the live list so a trip still recording keeps updating its summary.
            repository.trips.map { list -> list.firstOrNull { it.id == tripId } }.collect { trip ->
                loaded.value = if (trip == null) {
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

    fun setTilesEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setMapTilesEnabled(enabled) }
    }
}
