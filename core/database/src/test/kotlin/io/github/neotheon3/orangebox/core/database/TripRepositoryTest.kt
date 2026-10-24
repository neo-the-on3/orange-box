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

    @Test fun `batched reads return each trip's rows in time order (#166)`() = runBlocking {
        val db = open()
        val repo = RoomTripRepository(db.tripDao())
        val a = repo.start(TripEntity(vehicleId = null, startedAtMillis = 1_000, lastSampleAtMillis = 1_000))
        val b = repo.start(TripEntity(vehicleId = null, startedAtMillis = 9_000, lastSampleAtMillis = 9_000))
        fun s(trip: Long, t: Long) = TripSampleEntity(tripId = trip, timestampMillis = t, speedKph = 1.0, rpm = 1.0, fuelFlowLph = null, coolantC = null)
        repo.checkpoint(a, listOf(s(a.id, 3_000), s(a.id, 2_000)), listOf(TripEventEntity(tripId = a.id, timestampMillis = 2_500, type = "overspeed")))
        repo.checkpoint(b, listOf(s(b.id, 9_500)), emptyList())
        val samples = repo.samplesOf(listOf(a.id, b.id))
        assertEquals(listOf(2_000L, 3_000L), samples.getValue(a.id).map { it.timestampMillis })
        assertEquals(1, samples.getValue(b.id).size)
        assertEquals(1, repo.eventsOf(listOf(a.id, b.id)).getValue(a.id).size)
        assertNull(repo.eventsOf(listOf(b.id))[b.id])
        db.close()
    }

    @Test fun `period totals sum finished trips that started in the range (#46)`() = runBlocking {
        val db = open()
        val repo = RoomTripRepository(db.tripDao())
        suspend fun add(start: Long, km: Double, open: Boolean = false, accel: Int = 0, brake: Int = 0) {
            val t = repo.start(TripEntity(vehicleId = null, startedAtMillis = start, lastSampleAtMillis = start))
            repo.checkpoint(
                t.copy(
                    endedAtMillis = if (open) null else start + 600_000, distanceKm = km, durationS = 600.0, fuelLitres = km / 15,
                    ecoS = 300.0, idleS = 30.0, harshAccelCount = accel, harshBrakeCount = brake, overspeedCount = 1, maxSpeedKph = 50 + km,
                ),
                emptyList(), emptyList(),
            )
        }
        add(1_000, 10.0, accel = 1)
        add(5_000, 20.0, brake = 2)
        add(9_999, 30.0) // starts at the exclusive end: next period
        add(2_000, 40.0, open = true) // still recording: left out
        val totals = repo.totals(1_000, 9_999)
        assertEquals(2, totals.trips)
        assertEquals(30.0, totals.distanceKm, 1e-9)
        assertEquals(1_200.0, totals.durationS, 1e-9)
        assertEquals(2.0, totals.fuelLitres, 1e-9)
        assertEquals(600.0, totals.ecoS, 1e-9)
        assertEquals(3, totals.harshEvents)
        assertEquals(2, totals.overspeed)
        assertEquals(70.0, totals.maxSpeedKph, 1e-9)
        // An empty range is all zeros, not null.
        assertEquals(TripPeriodTotals(), repo.totals(20_000, 30_000))
        // The interface default (used by fakes) agrees with the query.
        val viaDefault = (object : TripRepository {
            override val trips = repo.trips
            override suspend fun start(trip: TripEntity) = error("")
            override suspend fun checkpoint(trip: TripEntity, samples: List<TripSampleEntity>, events: List<TripEventEntity>) = error("")
            override suspend fun closeOrphans() = 0
            override suspend fun trip(id: Long) = null
            override suspend fun samples(tripId: Long) = emptyList<TripSampleEntity>()
            override suspend fun events(tripId: Long) = emptyList<TripEventEntity>()
        }).totals(1_000, 9_999)
        assertEquals(totals.trips, viaDefault.trips)
        assertEquals(totals.distanceKm, viaDefault.distanceKm, 1e-9)
        assertEquals(totals.fuelLitres, viaDefault.fuelLitres, 1e-9)
        assertEquals(totals.harshEvents, viaDefault.harshEvents)
        db.close()
    }
}
