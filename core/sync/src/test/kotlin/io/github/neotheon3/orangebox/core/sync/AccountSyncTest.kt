package io.github.neotheon3.orangebox.core.sync

import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.network.VehicleCreateDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Per-account sync (Q13): each account syncs only its own trips; "Merge with account" edge cases. */
class AccountSyncTest {
    private var now = Instant.parse("2026-10-21T08:00:00Z")
    private val clock = { now.also { now = now.plusMillis(1) } }

    /** Two accounts are two servers (each fake holds one account). */
    private val serverA = FakeBackendApi(clock = clock)
    private val serverB = FakeBackendApi(clock = clock)
    private val phone = InMemorySyncStore()

    private fun engine(account: String?, server: FakeBackendApi) = SyncEngine(server, phone, account = account)

    @Test fun `only the signed-in account's trips go up, never a signed-out one or another account's`() = runTest {
        val car = phone.addVehicle(account = null)
        val other = phone.addVehicle("Swift", account = A)
        val signedOut = phone.record(car)
        val ofA = phone.record(other, account = A)
        val ofB = phone.record(car, account = B)
        phone.addTelemetry(signedOut, 5)
        phone.addTelemetry(ofA, 5)
        phone.addTelemetry(ofB, 5)

        engine(B, serverB).run()
        assertEquals(listOf(ofB), serverB.trips.map { it.clientTripId })
        assertEquals("only the vehicle of B's trip", listOf(car), serverB.vehicles.map { it.clientVehicleId })
        assertEquals(setOf(ofB), phone.telemetrySent.keys)
        assertTrue(phone.trips.getValue(signedOut).serverId == null && phone.trips.getValue(ofA).serverId == null)

        // Running again (periodic, trip end) still leaves them alone.
        engine(B, serverB).run()
        assertEquals(1, serverB.trips.size)
        assertTrue(serverA.trips.isEmpty())
    }

    @Test fun `a pulled trip is stamped with the account and never overwrites another account's trip`() = runTest {
        val car = phone.addVehicle(account = A)
        val mine = phone.record(car, account = A)
        // B has a trip with the same clientTripId (ids are unique per account, not across accounts).
        serverB.createVehicle(VehicleCreateDto("b-car", "Tata", "Nexon", "diesel"), "k")
        val bCar = serverB.vehicles.single().id
        serverB.seedTrip(trip(mine, bCar))
        serverB.seedTrip(trip("b-only", bCar))

        engine(B, serverB).run()
        val kept = phone.trips.getValue(mine)
        assertEquals(A, kept.account)
        assertFalse(kept.remoteOrigin)
        assertNull(kept.serverId)
        val pulled = phone.trips.getValue("b-only")
        assertEquals(B, pulled.account)
        assertTrue(pulled.remoteOrigin)
    }

    @Test fun `B's tombstones and recovery never delete A's trips`() = runTest {
        val car = phone.addVehicle(account = A)
        val ofA = phone.record(car, account = A)
        engine(A, serverA).run()
        assertTrue(phone.trips.getValue(ofA).uploadedBefore)
        engine(B, serverB).run()
        serverB.purgeTombstones()
        engine(B, serverB).run()
        assertTrue(ofA in phone.trips)
    }

    @Test fun `merge uploads the signed-out trips and their vehicle to the account and stamps them`() = runTest {
        val car = phone.addVehicle(account = null)
        val t1 = phone.record(car)
        val t2 = phone.record(car, at = Instant.parse("2026-10-20T12:00:00Z"))
        phone.mergeInto(B)
        engine(B, serverB).run()
        assertEquals(setOf(t1, t2), serverB.trips.map { it.clientTripId }.toSet())
        assertTrue(phone.trips.values.all { it.account == B && it.serverId != null })
        assertEquals(B, phone.vehicles.getValue(car).account)
    }

    @Test fun `merge restamps another account's trips and replaces its server ids with the new account's`() = runTest {
        val car = phone.addVehicle(account = A)
        val t = phone.record(car, account = A)
        engine(A, serverA).run()
        assertTrue(serverA.trips.single().id == phone.trips.getValue(t).serverId)
        // Sign-out clears vehicle server ids (RoomSyncStore.forgetServer); trip rows keep A's until merged.
        phone.vehicles[car] = phone.vehicles.getValue(car).copy(serverId = null)

        phone.mergeInto(B)
        assertNull("the old account's id is cleared on merge", phone.trips.getValue(t).serverId)
        engine(B, serverB).run()
        val inB = serverB.trips.single()
        assertEquals(t, inB.clientTripId)
        assertEquals(inB.id, phone.trips.getValue(t).serverId)
        assertEquals(serverB.vehicles.single().id, inB.vehicleId)
    }

    @Test fun `merge - a trip the account already has (unchanged or updated) is harmless`() = runTest {
        val car = phone.addVehicle(account = null)
        val t = phone.record(car)
        serverB.createVehicle(phone.vehicles.getValue(car).create, "k")
        serverB.seedTrip(phone.copyFor(t, serverB.vehicles.single().id))

        phone.mergeInto(B)
        val report = engine(B, serverB).run()
        assertEquals(0, report.rejected)
        assertEquals(1, serverB.trips.size)
        assertEquals(serverB.trips.single().id, phone.trips.getValue(t).serverId)
    }

    @Test fun `merge - a trip the account had deleted is deleted here and never re-uploaded under a fresh id`() = runTest {
        val car = phone.addVehicle(account = null)
        val gone = phone.record(car)
        val kept = phone.record(car, at = Instant.parse("2026-10-20T12:00:00Z"))
        serverB.createVehicle(phone.vehicles.getValue(car).create, "k")
        serverB.seedTrip(phone.copyFor(gone, serverB.vehicles.single().id))
        serverB.deleteTripElsewhere(gone)

        phone.mergeInto(B)
        engine(B, serverB).run()
        assertFalse(gone in phone.trips)
        assertEquals(listOf(kept), serverB.trips.map { it.clientTripId })
        engine(B, serverB).run()
        assertEquals("no re-upload under another id", 1, serverB.trips.size)
    }

    @Test fun `merge - a vehicle the account had deleted (410) is created again with a fresh clientVehicleId and its trips follow`() = runTest {
        val car = phone.addVehicle(account = null)
        val t = phone.record(car)
        serverB.createVehicle(phone.vehicles.getValue(car).create, "k")
        serverB.deleteVehicleElsewhere(car)

        phone.mergeInto(B)
        engine(B, serverB).run()
        val fresh = phone.vehicles.values.single()
        assertNotEquals(car, fresh.clientVehicleId)
        assertFalse(fresh.goneOnServer)
        assertFalse("the merge of the vehicle is done", fresh.merged)
        val created = serverB.vehicles.single()
        assertEquals(fresh.clientVehicleId, created.clientVehicleId)
        assertEquals(created.id, fresh.serverId)
        assertEquals(fresh.clientVehicleId, phone.trips.getValue(t).clientVehicleId)
        assertEquals(created.id, serverB.trips.single().vehicleId)
    }

    @Test fun `without a merge, a vehicle deleted in the account (410) is still given up`() = runTest {
        val car = phone.addVehicle(account = B)
        phone.record(car, account = B)
        serverB.createVehicle(phone.vehicles.getValue(car).create, "k")
        serverB.deleteVehicleElsewhere(car)
        engine(B, serverB).run()
        assertTrue(phone.vehicles.getValue(car).goneOnServer)
        assertTrue(serverB.trips.isEmpty())
    }

    private fun trip(clientTripId: String, vehicleId: String) = TripUploadDto(
        clientTripId = clientTripId, vehicleId = vehicleId, state = TripUploadDto.STATE_COMPLETED,
        startedAt = Instant.parse("2026-10-19T09:00:00Z"), snapshotAt = Instant.parse("2026-10-19T09:30:00Z"),
        endedAt = Instant.parse("2026-10-19T09:30:00Z"), distanceKm = 12.0,
    )

    private suspend fun InMemorySyncStore.copyFor(clientTripId: String, vehicleServerId: String): TripUploadDto {
        val v = trips.getValue(clientTripId).clientVehicleId!!
        val before = vehicles.getValue(v)
        vehicles[v] = before.copy(serverId = vehicleServerId)
        return uploadOf(clientTripId)!!.also { vehicles[v] = before }
    }

    private companion object {
        const val A = "member-a"
        const val B = "member-b"
    }
}
