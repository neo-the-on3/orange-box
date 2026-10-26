package io.github.neotheon3.orangebox.core.sync

import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.SyncWindowReason
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Sync rules from docs/backend/openapi-v2.yaml (Conventions → Incremental sync), run against FakeBackendApi. */
class SyncEngineTest {
    private var now = Instant.parse("2026-10-26T03:00:00Z")
    private val server = FakeBackendApi(clock = { now.also { now = now.plusMillis(1) } }, pageSize = 2)
    private val phone = InMemorySyncStore()
    private val engine = SyncEngine(server, phone)

    private fun otherDevice() = InMemorySyncStore().let { it to SyncEngine(server, it) }

    @Test fun `bootstrap - full walk, vehicle and trips uploaded, token stored`() = runTest {
        val car = phone.addVehicle()
        val trips = List(3) { phone.record(car) }
        val r = engine.run()

        assertTrue(server.calls.first().startsWith("GET /v1/trips?since=null&cursor=null"))
        assertNotNull(phone.since)
        assertEquals(1, r.vehiclesCreated)
        assertEquals(3, r.uploaded)
        assertEquals(trips.toSet(), server.trips.map { it.clientTripId }.toSet())
        assertTrue(phone.trips.values.all { it.uploadedBefore && it.serverId != null })
        assertEquals(server.vehicles.single().id, phone.vehicles.getValue(car).serverId)
    }

    @Test fun `incremental - trips walk to the last page, then deletions with the same since, then S' is stored`() = runTest {
        phone.record(phone.addVehicle())
        engine.run()
        val s = phone.since!!
        server.calls.clear()
        val (tablet, tabletEngine) = otherDevice()
        repeat(3) { tablet.record(tablet.addVehicle("Celerio")) }
        tabletEngine.run()
        server.calls.clear()

        engine.run()
        val walk = server.calls.filter { it.startsWith("GET /v1/trips") }
        assertEquals("GET /v1/trips?since=$s&cursor=null", walk.first())
        val firstDeletion = walk.indexOfFirst { it.startsWith("GET /v1/trips/deletions") }
        assertTrue("deletions after the trips walk", firstDeletion > 1)
        assertTrue("later trip pages use only the cursor", walk.subList(1, firstDeletion).all { it.startsWith("GET /v1/trips?since=null&cursor=") })
        assertTrue("same S on the deletions walk", walk.drop(firstDeletion).all { it.startsWith("GET /v1/trips/deletions?since=$s&") })
        assertNotEquals(s, phone.since)
        assertEquals(4, phone.trips.size)
        assertEquals(3, phone.trips.values.count { it.remoteOrigin })
    }

    @Test fun `at least once - a repeated item is applied once and an older updatedAt never wins`() = runTest {
        val (tablet, tabletEngine) = otherDevice()
        val t = tablet.record(tablet.addVehicle(), completed = false)
        tabletEngine.run()
        engine.run()
        val first = phone.trips.getValue(t).serverUpdatedAt!!
        // Replaying the same page (inclusive since) changes nothing.
        val r = SyncEngine(server, phone).run()
        assertEquals(0, r.pulled)
        assertEquals(first, phone.trips.getValue(t).serverUpdatedAt)
        // A newer snapshot from the tablet is applied.
        tablet.trips[t] = tablet.trips.getValue(t).copy(snapshotAt = Instant.parse("2026-10-20T11:00:00Z"))
        tabletEngine.run()
        engine.run()
        assertTrue(phone.trips.getValue(t).serverUpdatedAt!!.isAfter(first))
    }

    @Test fun `deleted on another device - the deletions walk removes it here`() = runTest {
        val t = phone.record(phone.addVehicle())
        engine.run()
        server.deleteTripElsewhere(t)
        val r = engine.run()
        assertEquals(1, r.deletedByServer)
        assertFalse(t in phone.trips)
    }

    @Test fun `deleted here - DELETE is pushed and the trip is not pulled back`() = runTest {
        val t = phone.record(phone.addVehicle())
        engine.run()
        phone.deleteHere(t)
        phone.since = null // force a full walk too
        engine.run()
        assertTrue(server.calls.any { it.startsWith("DELETE /v1/trips/") })
        assertTrue(t in server.tripTombstones)
        assertFalse(t in phone.trips)
        assertTrue(phone.deletions.getValue(t).pushed)
    }

    @Test fun `deployment_changed - full walk, lost vehicle re-created and remapped, lost trips re-uploaded, local deletions stay deleted`() = runTest {
        val car = phone.addVehicle()
        val kept = phone.record(car)
        engine.run()
        val backup = server.snapshot()
        val oldVehicleId = phone.vehicles.getValue(car).serverId
        // After the backup: a second car and more trips reach the server, then the database is restored.
        val car2 = phone.addVehicle("Celerio")
        val lost1 = phone.record(car2)
        val lost2 = phone.record(car)
        val deletedHere = phone.record(car)
        engine.run()
        phone.deleteHere(deletedHere)
        phone.deletions[deletedHere] = phone.deletions.getValue(deletedHere).copy(pushed = true, serverId = null) // pushed before the restore
        server.restoreFromBackup(backup)
        server.calls.clear()

        val r = engine.run()
        assertEquals(SyncWindowReason.DEPLOYMENT_CHANGED, r.recovery)
        assertTrue(server.calls.contains("GET /v1/trips?since=null&cursor=null"))
        assertEquals(1, r.vehiclesCreated)
        assertEquals(oldVehicleId, phone.vehicles.getValue(car).serverId)
        val newCar2 = phone.vehicles.getValue(car2).serverId!!
        assertEquals(setOf(kept, lost1, lost2), server.trips.map { it.clientTripId }.toSet())
        assertEquals(newCar2, server.trips.single { it.clientTripId == lost1 }.vehicleId)
        assertFalse("never re-uploaded", deletedHere in server.trips.map { it.clientTripId })
        assertEquals(server.trips.single { it.clientTripId == lost2 }.id, phone.trips.getValue(lost2).serverId)
        // The next run is a normal incremental one.
        server.calls.clear()
        assertNull(engine.run().recovery)
    }

    @Test fun `deployment_changed - 410 vehicle_deleted deletes the vehicle and its unreturned trips`() = runTest {
        val car = phone.addVehicle()
        engine.run() // nothing to upload yet; the vehicle isn't created without trips
        phone.record(car)
        engine.run()
        server.deleteVehicleElsewhere(car)
        // Restore to a point after the deletion: the vehicle row is gone, its tombstone survived.
        server.restoreFromBackup(server.snapshot())
        val r = engine.run()
        assertEquals(SyncWindowReason.DEPLOYMENT_CHANGED, r.recovery)
        assertFalse(car in phone.vehicles)
        assertTrue(phone.trips.isEmpty())
    }

    @Test fun `purged - drop uploaded trips the server no longer returns, keep never-uploaded ones, re-upload nothing`() = runTest {
        val car = phone.addVehicle()
        val goneElsewhere = phone.record(car)
        val stays = phone.record(car)
        engine.run()
        server.deleteTripElsewhere(goneElsewhere)
        server.purgeTombstones()
        val fresh = phone.record(car, at = Instant.parse("2026-10-25T10:00:00Z"))

        val r = engine.run()
        assertEquals(SyncWindowReason.PURGED, r.recovery)
        assertFalse(goneElsewhere in phone.trips)
        assertTrue(stays in phone.trips)
        assertTrue("never uploaded: kept and uploaded", fresh in server.trips.map { it.clientTripId })
        assertFalse(goneElsewhere in server.trips.map { it.clientTripId })
        assertEquals(1, r.dropped)
    }

    @Test fun `invalid, unknown and missing reasons are all invalid and handled like purged`() = runTest {
        for (reason in listOf("invalid", "something_new", null)) {
            val store = InMemorySyncStore()
            val api = FakeBackendApi(pageSize = 2)
            val car = store.addVehicle()
            val t = store.record(car)
            SyncEngine(api, store).run()
            api.deleteTripElsewhere(t)
            api.failNext(FakeBackendApi.syncWindowExpired(reason))
            val r = SyncEngine(api, store).run()
            assertEquals("$reason is treated as invalid", SyncWindowReason.INVALID, r.recovery)
            assertFalse(t in store.trips)
            assertEquals("nothing re-uploaded", 0, r.uploaded)
        }
    }

    @Test fun `expiry on a cursor mid-walk restarts the walk from page 1 without since`() = runTest {
        val (tablet, tabletEngine) = otherDevice()
        val car = tablet.addVehicle()
        repeat(5) { tablet.record(car) }
        tabletEngine.run()
        server.calls.clear()
        // Page 1 succeeds, page 2 (the cursor) expires.
        val real = server
        val failing = object : io.github.neotheon3.orangebox.core.network.BackendApi by real {
            var pages = 0
            override suspend fun listTrips(since: String?, cursor: String?) = real.listTrips(since, cursor).also {
                if (++pages == 2) throw FakeBackendApi.syncWindowExpired("invalid")
            }
        }
        SyncEngine(failing, phone).run()
        val starts = real.calls.filter { it == "GET /v1/trips?since=null&cursor=null" }
        assertEquals("restarted from page 1", 2, starts.size)
        assertEquals(5, phone.trips.size)
        assertNotNull(phone.since)
    }

    @Test fun `503 propagates for backoff and the retry reuses the same Idempotency-Key`() = runTest {
        val keys = mutableListOf<String>()
        val car = phone.addVehicle()
        phone.record(car)
        val recording = object : io.github.neotheon3.orangebox.core.network.BackendApi by server {
            var fail = true
            override suspend fun uploadTrips(batch: io.github.neotheon3.orangebox.core.network.TripBatchDto, idempotencyKey: String) =
                run {
                    keys += idempotencyKey
                    if (fail) { fail = false; throw FakeBackendApi.unavailable(7) }
                    server.uploadTrips(batch, idempotencyKey)
                }
        }
        val e = runCatching { SyncEngine(recording, phone).run() }.exceptionOrNull() as ApiError.Http
        assertEquals(503, e.status)
        assertTrue(e.isRetryable)
        assertEquals(7L, e.retryAfterSeconds)
        assertNotNull("batch kept for the retry", phone.pending)
        SyncEngine(recording, phone).run()
        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1])
        assertNull(phone.pending)
    }

    @Test fun `409 idempotency_in_flight on a vehicle is retryable`() = runTest {
        phone.record(phone.addVehicle())
        val inFlight = ApiError.Http(409, io.github.neotheon3.orangebox.core.network.ProblemDto(code = ApiError.CODE_IDEMPOTENCY_IN_FLIGHT, status = 409))
        val api = object : io.github.neotheon3.orangebox.core.network.BackendApi by server {
            override suspend fun createVehicle(vehicle: io.github.neotheon3.orangebox.core.network.VehicleCreateDto, idempotencyKey: String) = throw inFlight
        }
        try {
            SyncEngine(api, phone).run()
            fail()
        } catch (e: ApiError.Http) {
            assertTrue(e.isRetryable)
        }
        engine.run()
        assertEquals(1, server.trips.size)
    }

    @Test fun `410 on a normal vehicle push - not retried, its trips stay local`() = runTest {
        val car = phone.addVehicle()
        phone.record(car)
        engine.run()
        val car2 = phone.addVehicle("Celerio")
        val api = object : io.github.neotheon3.orangebox.core.network.BackendApi by server {
            var creates = 0
            override suspend fun createVehicle(vehicle: io.github.neotheon3.orangebox.core.network.VehicleCreateDto, idempotencyKey: String) = run {
                creates++
                throw ApiError.Http(410, io.github.neotheon3.orangebox.core.network.ProblemDto(code = ApiError.CODE_VEHICLE_DELETED, status = 410))
            }
        }
        val t = phone.record(car2)
        SyncEngine(api, phone).run()
        SyncEngine(api, phone).run()
        assertEquals(1, api.creates)
        assertTrue(phone.vehicles.getValue(car2).goneOnServer)
        assertTrue(t in phone.trips)
    }

    @Test fun `rejected trip_deleted drops the trip locally`() = runTest {
        val car = phone.addVehicle()
        val t = phone.record(car, completed = false)
        engine.run()
        server.deleteTripElsewhere(t)
        // A newer snapshot races the deletion; the upload is rejected with trip_deleted.
        phone.trips[t] = phone.trips.getValue(t).copy(snapshotAt = Instant.parse("2026-10-20T12:00:00Z"))
        val api = object : io.github.neotheon3.orangebox.core.network.BackendApi by server {
            override suspend fun listTripDeletions(since: String, cursor: String?) =
                io.github.neotheon3.orangebox.core.network.TripDeletionPage(emptyList(), null, "x") // deletion not seen yet
        }
        SyncEngine(api, phone).run()
        assertFalse(t in phone.trips)
    }

    @Test fun `in-progress snapshots are re-sent until completed, then the trip is final`() = runTest {
        val car = phone.addVehicle()
        val t = phone.record(car, completed = false)
        engine.run()
        assertEquals(TripUploadDto.STATE_IN_PROGRESS, server.trips.single().state)
        phone.trips[t] = phone.trips.getValue(t).copy(snapshotAt = Instant.parse("2026-10-20T10:30:00Z"), completed = true)
        assertEquals(1, engine.run().uploaded)
        assertEquals(TripUploadDto.STATE_COMPLETED, server.trips.single().state)
        assertEquals(0, engine.run().uploaded)
    }

    @Test fun `a batch 422 at trips index rejects only that trip and the rest go up in a fresh batch`() = runTest {
        val car = phone.addVehicle()
        phone.record(car)
        engine.run() // the vehicle now has a server id
        val fresh = List(3) { phone.record(car) }
        phone.trips[fresh[1]] = phone.trips.getValue(fresh[1]).copy(distanceKm = 123456789.123456789)
        server.maxTripBytes = phone.uploadOf(fresh[0])!!.encodedBytes()
        val r = engine.run()
        assertEquals(1, r.rejected)
        assertEquals(ApiError.CODE_VALIDATION_FAILED, phone.trips.getValue(fresh[1]).rejected)
        assertEquals(2, r.uploaded)
        assertNull(phone.pending)
        assertEquals("rejected trips aren't retried", 0, engine.run().uploaded)
    }

    @Test fun `free-text fields are cut to the server's limits`() {
        val t = TripUploadDto("c", "v", TripUploadDto.STATE_COMPLETED, now, now, app = io.github.neotheon3.orangebox.core.network.AppInfo("1".repeat(99), "a".repeat(99), "o".repeat(999)))
        val cut = t.withinLimits().app!!
        assertEquals(TripUploadDto.MAX_APP_VERSION, cut.version!!.length)
        assertEquals(TripUploadDto.MAX_APP_OS, cut.os!!.length)
        assertEquals(TripUploadDto.MAX_APP_OS_VERSION, cut.osVersion!!.length)
    }

    @Test fun `a vehicle deleted elsewhere is marked gone and its device-only trips stay local`() = runTest {
        val car = phone.addVehicle()
        val uploaded = phone.record(car)
        engine.run()
        val local = phone.record(car, at = Instant.parse("2026-10-21T10:00:00Z"))
        server.deleteVehicleElsewhere(car)
        val r = engine.run()
        assertEquals(1, r.vehiclesDeletedElsewhere)
        assertTrue(phone.vehicles.getValue(car).goneOnServer)
        assertFalse("its uploaded trip comes back as a tombstone", uploaded in phone.trips)
        assertTrue("device-only trip kept", local in phone.trips)
        assertFalse(local in server.trips.map { it.clientTripId })
        assertTrue(server.calls.contains("GET /v1/vehicles?includeArchived=true"))
    }

    @Test fun `trips of a vehicle deleted elsewhere upload after a move to another vehicle (#203)`() = runTest {
        val car = phone.addVehicle()
        phone.record(car)
        engine.run()
        val local = phone.record(car, at = Instant.parse("2026-10-21T10:00:00Z"))
        server.deleteVehicleElsewhere(car)
        engine.run()
        assertFalse(local in server.trips.map { it.clientTripId })

        val other = phone.addVehicle("Celerio")
        assertEquals("a gone vehicle is no target", 0, phone.moveTripsOfGoneVehicles(setOf(local), car))
        assertEquals(1, phone.moveTripsOfGoneVehicles(setOf(local), other))
        val r = engine.run()
        assertEquals(1, r.uploaded)
        assertEquals(1, r.vehiclesCreated)
        val serverOther = server.vehicles.single { it.clientVehicleId == other }
        assertEquals(serverOther.id, server.trips.single { it.clientTripId == local }.vehicleId)
        assertEquals("nothing left to move", 0, phone.moveTripsOfGoneVehicles(setOf(local), other))
    }

    @Test fun `an archived vehicle isn't deleted, and an edit elsewhere is applied once`() = runTest {
        val car = phone.addVehicle()
        phone.record(car)
        engine.run()
        server.editVehicleElsewhere(car) { it.copy(nickname = "Family car", archived = true) }
        val r = engine.run()
        assertEquals(0, r.vehiclesDeletedElsewhere)
        assertEquals(1, r.vehiclesEdited)
        assertEquals("Family car", phone.vehicleEdits.getValue(car).nickname)
        assertEquals(0, engine.run().vehiclesEdited)
    }

    @Test fun `deployment_changed - our own trips with a stale returned copy go up again, other devices' don't`() = runTest {
        val car = phone.addVehicle()
        val ongoing = phone.record(car, completed = false)
        val (tablet, tabletEngine) = otherDevice()
        tablet.record(tablet.addVehicle("Tablet car"))
        tabletEngine.run()
        engine.run()
        val backup = server.snapshot()
        // After the backup the trip completes here and that snapshot is accepted; then the database is restored.
        phone.trips[ongoing] = phone.trips.getValue(ongoing).copy(completed = true, snapshotAt = Instant.parse("2026-10-20T11:00:00Z"))
        assertEquals(1, engine.run().uploaded)
        server.restoreFromBackup(backup)

        val r = engine.run()
        assertEquals(SyncWindowReason.DEPLOYMENT_CHANGED, r.recovery)
        assertEquals("only our stale trip", 1, r.uploaded)
        val copy = server.trips.single { it.clientTripId == ongoing }
        assertEquals(TripUploadDto.STATE_COMPLETED, copy.state)
        assertEquals(Instant.parse("2026-10-20T11:00:00Z"), copy.snapshotAt)
    }

    @Test fun `a trip deleted while its batch response was lost - the batch is replayed with its key, then DELETE`() = runTest {
        val car = phone.addVehicle()
        val t = phone.record(car)
        engine.run() // vehicle and trip
        val again = phone.record(car)
        // The batch reaches the server but its response is lost: it stays pending, the trip has no server id.
        val pending = PendingTripBatch("lost-key", listOf(phone.uploadOf(again)!!))
        server.uploadTrips(io.github.neotheon3.orangebox.core.network.TripBatchDto(pending.trips), pending.idempotencyKey)
        phone.pending = pending
        phone.deleteHere(again)
        assertNull(phone.deletions.getValue(again).serverId)

        engine.run()
        assertFalse("deleted on the server too", again in server.trips.map { it.clientTripId })
        assertTrue(t in server.trips.map { it.clientTripId })
        assertTrue(phone.deletions.getValue(again).pushed)
        assertNull(phone.pending)
    }

    @Test fun `deleting a trip already gone on the server counts as done`() = runTest {
        val car = phone.addVehicle()
        val t = phone.record(car)
        engine.run()
        server.deleteTripElsewhere(t)
        phone.deleteHere(t)
        engine.run()
        assertTrue(phone.deletions.getValue(t).pushed)
    }
}
