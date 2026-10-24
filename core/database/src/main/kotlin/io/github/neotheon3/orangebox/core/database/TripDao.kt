package io.github.neotheon3.orangebox.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert suspend fun insertTrip(trip: TripEntity): Long
    @Update suspend fun updateTrip(trip: TripEntity)
    @Insert suspend fun insertSamples(samples: List<TripSampleEntity>)
    @Insert suspend fun insertEvents(events: List<TripEventEntity>)

    /** Samples, events and the summary checkpoint land together or not at all. */
    @Transaction
    suspend fun checkpoint(trip: TripEntity, samples: List<TripSampleEntity>, events: List<TripEventEntity>) {
        if (samples.isNotEmpty()) insertSamples(samples)
        if (events.isNotEmpty()) insertEvents(events)
        updateTrip(trip)
    }

    @Query("SELECT * FROM trips WHERE id = :id") suspend fun trip(id: Long): TripEntity?
    @Query("SELECT * FROM trips ORDER BY startedAtMillis DESC") fun observeTrips(): Flow<List<TripEntity>>
    @Query("SELECT * FROM trip_samples WHERE tripId = :tripId ORDER BY timestampMillis") suspend fun samples(tripId: Long): List<TripSampleEntity>
    @Query("SELECT * FROM trip_events WHERE tripId = :tripId ORDER BY timestampMillis") suspend fun events(tripId: Long): List<TripEventEntity>

    /** Samples of several trips in one query, grouped by trip then time (exports, #166). Keep [tripIds] short (SQLite's 999-variable limit). */
    @Query("SELECT * FROM trip_samples WHERE tripId IN (:tripIds) ORDER BY tripId, timestampMillis") suspend fun samplesOf(tripIds: List<Long>): List<TripSampleEntity>
    @Query("SELECT * FROM trip_events WHERE tripId IN (:tripIds) ORDER BY tripId, timestampMillis") suspend fun eventsOf(tripIds: List<Long>): List<TripEventEntity>

    /** Closes trips a dead process left open at their last checkpoint. Returns how many. */
    @Query("UPDATE trips SET endedAtMillis = lastSampleAtMillis, endReason = 'process_death' WHERE endedAtMillis IS NULL")
    suspend fun closeOrphans(): Int
}
