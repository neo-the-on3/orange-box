package io.github.neotheon3.orangebox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.SyncWindowReason
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** The engine's key scenarios against Room (schema 5) and FakeBackendApi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSyncStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file = File(context.cacheDir, "sync-test.db").also { it.delete() }
    private lateinit var db: OrangeBoxDatabase
    private lateinit var store: RoomSyncStore
    private var now = Instant.parse("2026-10-26T03:00:00Z")
    private val server = FakeBackendApi(clock = { now.also { now = now.plusMillis(1) } }, pageSize = 2)

    @Before fun open() {
        db = Room.databaseBuilder(context, OrangeBoxDatabase::class.java, file.absolutePath).allowMainThreadQueries().build()
        store = RoomSyncStore(db.syncDao())
    }

    @After fun cleanUp() {
        db.close()
        context.deleteDatabase(file.absolutePath)
    }

    private suspend fun car(name: String = "SX4") = db.vehicleDao().insert(
        VehicleEntity(name = name, maker = "Maruti Suzuki", model = name, fuel = "PETROL", displacementCc = 1586, odometerKm = null, createdAtMillis = 1),
    )

    private suspend fun trip(vehicle: Long, start: Long = 1_760_000_000_000, ended: Boolean = true) = db.tripDao().insertTrip(
        TripEntity(vehicleId = vehicle, startedAtMillis = start, lastSampleAtMillis = start + 600_000, endedAtMillis = if (ended) start + 600_000 else null,
            durationS = 600.0, distanceKm = 5.0, fuelLitres = 0.4),
    ).let { db.tripDao().trip(it)!! }

    private fun run() = runBlocking { SyncEngine(server, store).run() }

    @Test fun `bootstrap uploads the vehicle and trips and stores the token`() = runBlocking {
        val v = car()
        val t = trip(v)
        val r = run()
        assertEquals(1, r.vehiclesCreated)
        assertEquals(1, r.uploaded)
        assertNotNull(store.since())
        val up = server.trips.single()
        assertEquals(t.clientTripId, up.clientTripId)
        assertEquals(TripUploadDto.STATE_COMPLETED, up.state)
        assertEquals(5.0, up.distanceKm!!, 1e-9)
        assertEquals(server.vehicles.single().id, up.vehicleId)
        assertTrue(store.trips().single().uploadedBefore)
        assertFalse(store.trips().single().needsUpload)
        assertNull(store.pendingTripBatch())
    }

    @Test fun `an in-progress trip is re-sent as completed with a newer snapshot`() = runBlocking {
        val v = car()
        val t = trip(v, ended = false)
        run()
        assertEquals(TripUploadDto.STATE_IN_PROGRESS, server.trips.single().state)
        db.tripDao().updateTrip(t.copy(endedAtMillis = t.lastSampleAtMillis, endReason = "engine_off"))
        assertEquals(1, run().uploaded)
        assertEquals(TripUploadDto.STATE_COMPLETED, server.trips.single().state)
        assertEquals(0, run().uploaded)
    }

    @Test fun `editing a vehicle keeps its clientVehicleId`() = runBlocking {
        val id = car()
        val before = db.vehicleDao().get(id)!!.clientVehicleId
        val repo = RoomVehicleRepository(db.vehicleDao(), io.github.neotheon3.orangebox.core.testing.FakeAppPreferencesRepository())
        repo.save(db.vehicleDao().get(id)!!.toModel().copy(name = "My SX4"))
        assertEquals(before, db.vehicleDao().get(id)!!.clientVehicleId)
    }

    @Test fun `trips from another device are pulled and a deletion elsewhere removes them`() = runBlocking {
        val v = car()
        trip(v)
        run()
        val serverVehicle = server.vehicles.single().id
        server.seedTrip(
            TripUploadDto(clientTripId = "6c1b0f1e-0000-4000-8000-000000000042", vehicleId = serverVehicle, state = "completed",
                startedAt = Instant.parse("2026-10-24T08:00:00Z"), snapshotAt = Instant.parse("2026-10-24T08:30:00Z"),
                endedAt = Instant.parse("2026-10-24T08:30:00Z"), distanceKm = 21.0),
        )
        run()
        val pulled = db.syncDao().tripByClientId("6c1b0f1e-0000-4000-8000-000000000042")!!
        assertEquals(21.0, pulled.distanceKm, 1e-9)
        assertEquals(v, pulled.vehicleId)
        assertEquals(RoomSyncStore.END_REMOTE, pulled.endReason)
        assertTrue(store.trips().single { it.clientTripId == pulled.clientTripId }.remoteOrigin)
        server.deleteTripElsewhere(pulled.clientTripId)
        run()
        assertNull(db.syncDao().tripByClientId(pulled.clientTripId))
    }

    @Test fun `deleted here - DELETE is pushed and never pulled back`() = runBlocking {
        val t = trip(car())
        run()
        db.syncDao().deleteTripHere(t.clientTripId, nowMillis = 5)
        store.clearSince()
        run()
        assertTrue(t.clientTripId in server.tripTombstones)
        assertNull(db.syncDao().tripByClientId(t.clientTripId))
        assertTrue(store.localDeletions().single().pushed)
    }

    @Test fun `deployment_changed re-creates the vehicle and re-uploads lost trips`() = runBlocking {
        val backup = server.snapshot()
        val v = car()
        val t = trip(v)
        run()
        server.restoreFromBackup(backup)
        val r = run()
        assertEquals(SyncWindowReason.DEPLOYMENT_CHANGED, r.recovery)
        assertEquals(t.clientTripId, server.trips.single().clientTripId)
        assertEquals(server.vehicles.single().id, server.trips.single().vehicleId)
        assertEquals(server.trips.single().id, store.trips().single().serverId)
    }

    @Test fun `purged drops uploaded trips the server lost and keeps new ones`() = runBlocking {
        val v = car()
        val old = trip(v)
        run()
        server.deleteTripElsewhere(old.clientTripId)
        server.purgeTombstones()
        val fresh = trip(v, start = 1_760_100_000_000)
        val r = run()
        assertEquals(SyncWindowReason.PURGED, r.recovery)
        assertNull(db.syncDao().tripByClientId(old.clientTripId))
        assertNotNull(db.syncDao().tripByClientId(fresh.clientTripId))
        assertEquals(listOf(fresh.clientTripId), server.trips.map { it.clientTripId })
    }

    @Test fun `the pending batch survives a reopen with its Idempotency-Key`() = runBlocking {
        trip(car())
        val batch = io.github.neotheon3.orangebox.core.sync.PendingTripBatch("key-1", listOf(store.trips().let {
            store.setVehicleServerId(store.vehicles().single().clientVehicleId, "veh_x"); store.uploadOf(it.single().clientTripId)!!
        }))
        store.setPendingTripBatch(batch)
        db.close()
        open()
        assertEquals(batch, store.pendingTripBatch())
    }

    @Test fun `fuel types map to the spec enum`() {
        assertEquals("gasoline", RoomSyncStore.wireFuel("PETROL"))
        assertEquals("diesel", RoomSyncStore.wireFuel("DIESEL"))
        assertEquals("lpg", RoomSyncStore.wireFuel("LPG"))
        assertEquals("other", RoomSyncStore.wireFuel("CNG"))
        assertEquals("gasoline", RoomSyncStore.wireFuel("SOMETHING_OLD"))
    }
}
