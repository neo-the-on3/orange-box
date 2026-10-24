package io.github.neotheon3.orangebox.core.database

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Trip storage for the recorder (and, later, the Trips screen). */
interface TripRepository {
    val trips: Flow<List<TripEntity>>

    /** Inserts an open trip; returns it with its id. */
    suspend fun start(trip: TripEntity): TripEntity

    /** Writes [samples] and [events] and the summary in [trip] atomically. */
    suspend fun checkpoint(trip: TripEntity, samples: List<TripSampleEntity>, events: List<TripEventEntity>)

    /** Closes trips left open by process death. Returns how many. */
    suspend fun closeOrphans(): Int

    suspend fun trip(id: Long): TripEntity?
    suspend fun samples(tripId: Long): List<TripSampleEntity>
    suspend fun events(tripId: Long): List<TripEventEntity>

    /** Samples of a batch of trips (a few at a time), in time order per trip. */
    suspend fun samplesOf(tripIds: List<Long>): Map<Long, List<TripSampleEntity>> = tripIds.associateWith { samples(it) }

    /** Events of a batch of trips (a few at a time), in time order per trip. */
    suspend fun eventsOf(tripIds: List<Long>): Map<Long, List<TripEventEntity>> = tripIds.associateWith { events(it) }
}

class RoomTripRepository @Inject constructor(private val dao: TripDao) : TripRepository {
    override val trips: Flow<List<TripEntity>> = dao.observeTrips()
    override suspend fun start(trip: TripEntity) = trip.copy(id = dao.insertTrip(trip.copy(id = 0)))
    override suspend fun checkpoint(trip: TripEntity, samples: List<TripSampleEntity>, events: List<TripEventEntity>) =
        dao.checkpoint(trip, samples, events)
    override suspend fun closeOrphans() = dao.closeOrphans()
    override suspend fun trip(id: Long) = dao.trip(id)
    override suspend fun samples(tripId: Long) = dao.samples(tripId)
    override suspend fun events(tripId: Long) = dao.events(tripId)
    override suspend fun samplesOf(tripIds: List<Long>) = dao.samplesOf(tripIds).groupBy { it.tripId }
    override suspend fun eventsOf(tripIds: List<Long>) = dao.eventsOf(tripIds).groupBy { it.tripId }
}
