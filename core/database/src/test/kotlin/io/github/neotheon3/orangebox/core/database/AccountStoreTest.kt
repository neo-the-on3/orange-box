package io.github.neotheon3.orangebox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.model.Vehicle
import io.github.neotheon3.orangebox.core.model.VehicleFuel
import io.github.neotheon3.orangebox.core.network.DtcReportCreateDto
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.sync.SyncEngine
import io.github.neotheon3.orangebox.core.testing.FakeAppPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/** Account stamps on trips and vehicles (Q13): recording, sign-out, merge and replace, against Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file = File(context.cacheDir, "account-test.db").also { it.delete() }
    private lateinit var db: OrangeBoxDatabase
    private lateinit var store: RoomSyncStore
    private lateinit var trips: RoomTripRepository
    private lateinit var vehicles: RoomVehicleRepository
    private var now = Instant.parse("2026-10-26T03:00:00Z")
    private val clock = { now.also { now = now.plusMillis(1) } }
    private val serverA = FakeBackendApi(clock = clock)
    private val serverB = FakeBackendApi(clock = clock)
    private val clockMillis = 1_760_000_000_000 + 86_400_000

    @Before fun open() {
        db = Room.databaseBuilder(context, OrangeBoxDatabase::class.java, file.absolutePath).allowMainThreadQueries().build()
        store = RoomSyncStore(db.syncDao()) { clockMillis }
        trips = RoomTripRepository(db.tripDao())
        vehicles = RoomVehicleRepository(db.vehicleDao(), FakeAppPreferencesRepository())
    }

    @After fun cleanUp() {
        db.close()
        context.deleteDatabase(file.absolutePath)
    }

    private suspend fun car(name: String = "SX4") = vehicles.save(Vehicle(name = name, maker = "Maruti Suzuki", model = name, fuel = VehicleFuel.PETROL))

    private suspend fun record(vehicle: Long, start: Long = 1_760_000_000_000): TripEntity {
        val t = trips.start(TripEntity(vehicleId = vehicle, startedAtMillis = start, lastSampleAtMillis = start))
        val ended = t.copy(endedAtMillis = start + 600_000, lastSampleAtMillis = start + 600_000, durationS = 600.0, distanceKm = 5.0)
        trips.checkpoint(ended, listOf(TripSampleEntity(tripId = t.id, timestampMillis = start + 1000, speedKph = 40.0, rpm = 1800.0, fuelFlowLph = 2.0, coolantC = 85.0)), emptyList())
        return ended
    }

    private suspend fun stampOf(trip: TripEntity) = db.tripDao().trip(trip.id)!!.accountId
    private fun run(account: String?, server: FakeBackendApi) = runBlocking { SyncEngine(server, store, account = account).run() }

    @Test fun `trips and vehicles are stamped with the signed-in account, null when signed out, and edits keep it`() = runBlocking {
        val signedOutCar = car()
        val signedOut = record(signedOutCar)
        assertNull(stampOf(signedOut))
        assertNull(db.vehicleDao().get(signedOutCar)!!.accountId)

        store.setAccount(A)
        val carA = car("Swift")
        val ofA = record(carA, start = 1_760_000_100_000)
        assertEquals(A, stampOf(ofA))
        assertEquals(A, db.vehicleDao().get(carA)!!.accountId)
        vehicles.save(db.vehicleDao().get(carA)!!.toModel().copy(name = "Swift Dzire"))
        assertEquals("editing a vehicle keeps its account", A, db.vehicleDao().get(carA)!!.accountId)

        // A merge while recording: the recorder's next checkpoint doesn't undo it.
        val open = trips.start(TripEntity(vehicleId = carA, startedAtMillis = 1_760_000_200_000, lastSampleAtMillis = 1_760_000_200_000))
        store.mergeInto(B)
        trips.checkpoint(open.copy(lastSampleAtMillis = 1_760_000_201_000), emptyList(), emptyList())
        assertEquals(B, stampOf(open))
    }

    @Test fun `sign-out keeps every trip with its account and server ids, and forgets the session`() = runBlocking {
        store.setAccount(A)
        val v = car()
        val mine = record(v)
        run(A, serverA)
        serverA.seedTrip(otherDevicesTrip(serverA.vehicles.single().id))
        run(A, serverA)
        assertEquals(2, store.trips().size)

        db.syncDao().forgetServer()
        assertNull(store.account())
        assertNull(store.since())
        val kept = store.trips()
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.account == A && it.serverId != null })
        assertTrue("pulled trips stay too", kept.any { it.remoteOrigin })
        assertTrue("vehicle ids are per sign-in", store.vehicles().all { it.serverId == null })
        assertFalse(kept.single { it.clientTripId == mine.clientTripId }.needsUpload)
    }

    @Test fun `trips not of the account are counted, with those that exist nowhere else`() = runBlocking {
        val v = car()
        record(v) // signed out
        store.setAccount(A)
        record(v, start = 1_760_000_100_000)
        run(A, serverA) // uploaded to A
        record(v, start = 1_760_000_200_000).also { db.syncDao().forgetServer() } // A, never uploaded
        store.setAccount(B)
        val foreign = store.foreignTrips(B)
        assertEquals(3, foreign.count)
        assertEquals(2, foreign.neverUploaded)
        assertEquals(3, store.observeForeignTripCount(B).first())
        assertEquals("the signed-out one is foreign to A as well", 1, store.foreignTrips(A).count)
    }

    @Test fun `merge restamps the trips and vehicles, drops the old account's ids, then they upload to the new account`() = runBlocking {
        store.setAccount(A)
        val v = car()
        val t = record(v)
        run(A, serverA)
        val idInA = store.trips().single().serverId
        db.syncDao().forgetServer()
        store.setAccount(B)

        assertEquals(1, store.mergeInto(B))
        assertEquals(B, stampOf(t))
        assertEquals(B, db.vehicleDao().get(v)!!.accountId)
        val merged = store.trips().single()
        assertNull("the old account's server id is gone", merged.serverId)
        assertTrue(merged.needsUpload)
        assertTrue(store.vehicles().single().merged)

        run(B, serverB)
        val inB = serverB.trips.single()
        assertEquals(t.clientTripId, inB.clientTripId) // reused: unique per account
        assertEquals(inB.id, store.trips().single().serverId)
        assertNotNull(idInA)
        assertFalse(store.vehicles().single().merged)
        assertEquals("telemetry starts over for the new account", 1, serverB.telemetryRequests.size)
        assertEquals(0, store.foreignTrips(B).count)
    }

    @Test fun `merge - a vehicle the account had deleted is re-created under a fresh id, trips and DTC reports follow`() = runBlocking {
        val v = car()
        val t = record(v)
        val oldId = db.vehicleDao().get(v)!!.clientVehicleId
        store.enqueueDtcReport(v, DtcReportCreateDto("00000000-0000-4000-8000-000000000001", DtcReportCreateDto.KIND_READ, Instant.ofEpochMilli(clockMillis - 1000), mil = false))
        serverB.createVehicle(store.vehicles().single().create, "k")
        serverB.deleteVehicleElsewhere(oldId)
        store.setAccount(B)
        store.mergeInto(B)

        run(B, serverB)
        val renewed = db.vehicleDao().get(v)!!
        assertNotEquals(oldId, renewed.clientVehicleId)
        val created = serverB.vehicles.single()
        assertEquals(renewed.clientVehicleId, created.clientVehicleId)
        assertEquals(created.id, serverB.trips.single { it.clientTripId == t.clientTripId }.vehicleId)
        assertEquals(1, serverB.dtcReports.size)
    }

    @Test fun `merge - a trip the account had deleted is deleted here, not re-uploaded`() = runBlocking {
        val v = car()
        val t = record(v)
        serverB.createVehicle(store.vehicles().single().create, "k")
        serverB.seedTrip(TripUploadDto(t.clientTripId, serverB.vehicles.single().id, TripUploadDto.STATE_COMPLETED, Instant.ofEpochMilli(t.startedAtMillis), Instant.ofEpochMilli(t.lastSampleAtMillis)))
        serverB.deleteTripElsewhere(t.clientTripId)
        store.setAccount(B)
        store.mergeInto(B)
        run(B, serverB)
        assertNull(db.tripDao().trip(t.id))
        assertTrue(serverB.trips.isEmpty())
        assertTrue("not a user deletion: no DELETE to push", db.syncDao().localDeletions().isEmpty())
    }

    @Test fun `replace deletes only the trips that aren't the account's, keeps vehicles and adopts unassigned ones`() = runBlocking {
        val v = car()
        val signedOut = record(v)
        store.setAccount(B)
        val mine = record(v, start = 1_760_000_100_000)
        assertEquals(1, store.replaceWith(B))
        assertNull(db.tripDao().trip(signedOut.id))
        assertNotNull(db.tripDao().trip(mine.id))
        assertEquals(B, db.vehicleDao().get(v)!!.accountId)
        assertTrue("no tombstones: the server copies are another account's", db.syncDao().localDeletions().isEmpty())
    }

    @Test fun `a pulled trip doesn't overwrite another account's trip with the same id, and deleting a foreign trip pushes nothing`() = runBlocking {
        store.setAccount(A)
        val v = car()
        val ofA = record(v)
        db.syncDao().forgetServer()
        store.setAccount(B)
        serverB.createVehicle(store.vehicles().single().create, "k")
        serverB.seedTrip(TripUploadDto(ofA.clientTripId, serverB.vehicles.single().id, TripUploadDto.STATE_COMPLETED, Instant.ofEpochMilli(1), Instant.ofEpochMilli(2), distanceKm = 99.0))
        run(B, serverB)
        val local = db.tripDao().trip(ofA.id)!!
        assertEquals(A, local.accountId)
        assertEquals(5.0, local.distanceKm, 0.0)

        db.syncDao().deleteTripHere(ofA.clientTripId, nowMillis = 5)
        val d = db.syncDao().localDeletions().single()
        assertNull(d.serverId)
        assertTrue(d.pushed)
    }

    private fun otherDevicesTrip(vehicleId: String) = TripUploadDto(
        "other-device-trip", vehicleId, TripUploadDto.STATE_COMPLETED, Instant.parse("2025-10-09T09:00:00Z"), Instant.parse("2025-10-09T09:30:00Z"),
    )

    private companion object {
        const val A = "member-a"
        const val B = "member-b"
    }
}
