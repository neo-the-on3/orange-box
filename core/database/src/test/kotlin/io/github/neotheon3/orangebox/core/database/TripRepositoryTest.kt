package io.github.neotheon3.orangebox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file = File(context.cacheDir, "trips-test.db").also { it.delete() }
    private fun open() = Room.databaseBuilder(context, OrangeBoxDatabase::class.java, file.absolutePath).allowMainThreadQueries().build()

    @After fun cleanUp() { context.deleteDatabase(file.absolutePath) }

    @Test fun `checkpoint writes samples, events and summary together`() = runBlocking {
        val db = open()
        val repo = RoomTripRepository(db.tripDao())
        val trip = repo.start(TripEntity(vehicleId = null, startedAtMillis = 1_000, lastSampleAtMillis = 1_000))
        repo.checkpoint(
            trip.copy(lastSampleAtMillis = 3_000, distanceKm = 0.04),
            listOf(TripSampleEntity(tripId = trip.id, timestampMillis = 2_000, speedKph = 72.0, rpm = 2000.0, fuelFlowLph = 3.3, coolantC = 85.0, latitude = 12.9, longitude = 77.6)),
            listOf(TripEventEntity(tripId = trip.id, timestampMillis = 2_500, type = "overspeed", value = 125.0)),
        )
        assertEquals(0.04, repo.trip(trip.id)!!.distanceKm, 1e-9)
        assertEquals(1, repo.samples(trip.id).size)
        assertEquals("overspeed", repo.events(trip.id).single().type)
        assertEquals(1, repo.trips.first().size)
        db.close()
    }

    @Test fun `process death mid trip keeps the last checkpoint and closes it on next start`() = runBlocking {
        val first = open()
        val repo = RoomTripRepository(first.tripDao())
        val trip = repo.start(TripEntity(vehicleId = 7, startedAtMillis = 1_000, lastSampleAtMillis = 1_000))
        repo.checkpoint(trip.copy(lastSampleAtMillis = 61_000, durationS = 60.0, distanceKm = 1.2, fuelLitres = 0.08), emptyList(), emptyList())
        // The process dies here: no final update, the database is simply not closed cleanly.
        val second = open()
        val repo2 = RoomTripRepository(second.tripDao())
        assertNull(repo2.trip(trip.id)!!.endedAtMillis)
        assertEquals(1, repo2.closeOrphans())
        val closed = repo2.trip(trip.id)!!
        assertEquals(61_000L, closed.endedAtMillis)
        assertEquals("process_death", closed.endReason)
        assertEquals(1.2, closed.distanceKm, 1e-9)
        assertEquals(0, repo2.closeOrphans())
        first.close(); second.close()
    }
}
