package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [TripRepository] for screen and ViewModel tests. [trips] is newest first, like Room's. */
class FakeTripRepository(
    trips: List<TripEntity> = emptyList(),
    val samplesByTrip: MutableMap<Long, List<TripSampleEntity>> = mutableMapOf(),
    val eventsByTrip: MutableMap<Long, List<TripEventEntity>> = mutableMapOf(),
) : TripRepository {
    override val trips = MutableStateFlow(trips.sortedByDescending { it.startedAtMillis })
    private var nextId = (trips.maxOfOrNull { it.id } ?: 0) + 1

    fun set(trips: List<TripEntity>) { this.trips.value = trips.sortedByDescending { it.startedAtMillis } }

    override suspend fun start(trip: TripEntity): TripEntity {
        val created = trip.copy(id = nextId++)
        trips.update { (it + created).sortedByDescending { t -> t.startedAtMillis } }
        return created
    }

    override suspend fun checkpoint(trip: TripEntity, samples: List<TripSampleEntity>, events: List<TripEventEntity>) {
        samplesByTrip[trip.id] = samplesByTrip[trip.id].orEmpty() + samples
        eventsByTrip[trip.id] = eventsByTrip[trip.id].orEmpty() + events
        trips.update { list -> list.map { if (it.id == trip.id) trip else it } }
    }

    override suspend fun closeOrphans(): Int = 0
    override suspend fun trip(id: Long) = trips.value.firstOrNull { it.id == id }
    override suspend fun samples(tripId: Long) = samplesByTrip[tripId].orEmpty()
    override suspend fun events(tripId: Long) = eventsByTrip[tripId].orEmpty()
    override suspend fun setExcludedFromStats(id: Long, excluded: Boolean) {
        trips.update { list -> list.map { if (it.id == id) it.copy(excludedFromStats = excluded) else it } }
    }
    override suspend fun delete(id: Long) {
        trips.update { list -> list.filterNot { it.id == id && it.endedAtMillis != null } }
    }
}
