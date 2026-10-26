package io.github.neotheon3.orangebox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.network.DtcReportCreateDto
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.SyncWindowReason
import io.github.neotheon3.orangebox.core.network.TripUploadDto
import io.github.neotheon3.orangebox.core.sync.SyncEngine
import kotlinx.coroutines.flow.first
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
        store = RoomSyncStore(db.syncDao()) { clockMillis }
    }

    /** The store's clock (telemetry backlog bound): a day after the test trips. */
    private var clockMillis = 1_760_000_000_000 + 86_400_000

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

    @Test fun `#203 - trips of a vehicle deleted elsewhere move to another vehicle and upload`() = runBlocking {
        val car = car()
        trip(car)
        run()
        val local = trip(car, start = 1_760_000_900_000)
        val clientCar = db.syncDao().clientVehicleIdOf(car)!!
        server.deleteVehicleElsewhere(clientCar)
        assertEquals(1, run().vehiclesDeletedElsewhere)
        val reassign = RoomTripReassignment(db.syncDao())
        assertEquals(setOf(local.id), reassign.orphanedTripIds.first())
        assertNull("can't upload without a server vehicle", store.uploadOf(local.clientTripId))

        val celerio = car("Celerio")
        assertEquals("only vehicles that aren't gone", listOf(celerio), reassign.targets(setOf(local.id)).map { it.id })
        assertEquals("a gone vehicle is no target", 0, reassign.move(setOf(local.id), car))
        assertEquals(1, reassign.move(setOf(local.id), celerio))
        assertEquals(celerio, db.tripDao().trip(local.id)!!.vehicleId)
        assertTrue(reassign.orphanedTripIds.first().isEmpty())

        val r = run()
        assertEquals(1, r.uploaded)
        val serverCelerio = server.vehicles.single { it.clientVehicleId == db.syncDao().clientVehicleIdOf(celerio) }
        assertEquals(serverCelerio.id, server.trips.single { it.clientTripId == local.clientTripId }.vehicleId)
        assertEquals("only orphaned trips move", 0, reassign.move(setOf(local.id), celerio))
    }

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
        assertEquals("no endReason from the server reads as other", "other", pulled.endReason)
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
        assertEquals("bi-fuel CNG presets", "gasoline_cng", RoomSyncStore.wireFuel("CNG"))
        assertEquals("ethanol", RoomSyncStore.wireFuel("ETHANOL"))
        assertEquals("gasoline", RoomSyncStore.wireFuel("SOMETHING_OLD"))
        assertEquals("CNG", RoomSyncStore.localFuel("cng"))
        assertEquals("CNG", RoomSyncStore.localFuel("gasoline_cng"))
        assertEquals("ETHANOL", RoomSyncStore.localFuel("flex"))
        assertEquals("unknown server values are tolerated", null, RoomSyncStore.localFuel("hydrogen"))
        assertEquals("other", RoomSyncStore.wireEndReason("demo"))
        assertEquals("disconnected", RoomSyncStore.wireEndReason("disconnected"))
    }

    // --- telemetry (#67) ---

    private suspend fun telemetry(trip: TripEntity, samples: Int, events: List<String> = emptyList()) {
        db.tripDao().insertSamples(List(samples) {
            TripSampleEntity(tripId = trip.id, timestampMillis = trip.startedAtMillis + it * 1000L, speedKph = 40.0, rpm = 1800.0, fuelFlowLph = 2.1,
                coolantC = 85.0, latitude = 12.97, longitude = 77.59)
        })
        db.tripDao().insertEvents(events.mapIndexed { i, type -> TripEventEntity(tripId = trip.id, timestampMillis = trip.startedAtMillis + i * 5000L, type = type, value = -4.0) })
    }

    @Test fun `telemetry of a finished trip is uploaded once, with clientEventIds and without fuel_cut`() = runBlocking {
        val t = trip(car())
        telemetry(t, samples = 7_000, events = listOf("harsh_brake", "fuel_cut", "overspeed"))
        val r = run()
        assertEquals(2, r.telemetryBatches)
        val stored = server.telemetry.getValue(t.clientTripId)
        assertEquals(7_000, stored.samples.size)
        assertEquals(listOf("harsh_brake", "overspeed"), stored.events.map { it.type })
        val ids = db.tripDao().events(t.id).filter { it.type != "fuel_cut" }.map { it.clientEventId }
        assertEquals(ids, stored.events.map { it.clientEventId })
        val sample = stored.samples.values.first()
        assertEquals(40.0, sample.speedKmh!!, 0.0)
        assertEquals(85.0, sample.coolantC!!, 0.0)
        assertEquals(0, run().telemetryBatches)
    }

    @Test fun `a trip with only unsendable events is skipped without a request`() = runBlocking {
        val t = trip(car())
        telemetry(t, samples = 0, events = listOf("fuel_cut", "fuel_cut"))
        assertEquals(0, run().telemetryBatches)
        assertNull(store.telemetryChunk(t.clientTripId, 10, 10))
    }

    @Test fun `in-progress trips and trips past the backlog bound send no telemetry`() = runBlocking {
        val v = car()
        telemetry(trip(v, ended = false), 5)
        telemetry(trip(v, start = clockMillis - (RoomSyncStore.TELEMETRY_BACKLOG_DAYS + 1) * 86_400_000), 5)
        run()
        assertTrue(server.telemetry.isEmpty())
    }

    @Test fun `the pending telemetry batch and progress survive a reopen`() = runBlocking {
        val t = trip(car())
        telemetry(t, 10, listOf("harsh_accel"))
        server.failNextTelemetry(FakeBackendApi.unavailable(5))
        runCatching { run() }
        val pending = store.pendingTelemetryBatch()
        assertNotNull(pending)
        db.close()
        open()
        assertEquals(pending, store.pendingTelemetryBatch())
        run()
        assertNull(store.pendingTelemetryBatch())
        assertEquals(2, server.telemetryRequests.size)
        assertEquals(server.telemetryRequests[0], server.telemetryRequests[1])
        assertNull("all sent", store.telemetryChunk(t.clientTripId, 100, 100))
    }

    @Test fun `a 422 marks the trip done so it is not offered again`() = runBlocking {
        val t = trip(car())
        telemetry(t, 3)
        server.failNextTelemetry(io.github.neotheon3.orangebox.core.network.ApiError.Http(422, null))
        assertEquals(1, run().telemetryRejected)
        assertTrue(store.telemetryTrips().none { it.clientTripId == t.clientTripId })
    }

    private fun dtc(id: String, atMillis: Long = clockMillis - 60_000) =
        DtcReportCreateDto(id, DtcReportCreateDto.KIND_READ, Instant.ofEpochMilli(atMillis), mil = true, stored = listOf("P0300"))

    @Test fun `a queued DTC report survives a reopen and goes up once with its vehicle (#67)`() = runBlocking {
        val v = car()
        assertTrue(store.enqueueDtcReport(v, dtc("00000000-0000-4000-8000-0000000000d1")))
        assertFalse("unknown vehicle", store.enqueueDtcReport(9_999, dtc("00000000-0000-4000-8000-0000000000d2")))
        db.close()
        open()
        assertEquals(1, store.dtcReports().size)
        val r = run()
        assertEquals(1, r.vehiclesCreated)
        assertEquals(1, r.dtcReports)
        assertEquals(listOf("P0300"), server.dtcReports.single().stored)
        assertTrue(store.dtcReports().isEmpty())
    }

    @Test fun `the DTC queue is bounded, drops stale reports and those of deleted vehicles, and sign-out clears it`() = runBlocking {
        val v = car()
        repeat(RoomSyncStore.MAX_QUEUED_DTC_REPORTS + 5) {
            store.enqueueDtcReport(v, dtc("00000000-0000-4000-8000-%012d".format(it), atMillis = clockMillis - 3_600_000 + it))
        }
        val queued = store.dtcReports()
        assertEquals(RoomSyncStore.MAX_QUEUED_DTC_REPORTS, queued.size)
        assertEquals("oldest dropped", "00000000-0000-4000-8000-%012d".format(5), queued.first().report.clientReportId)

        store.enqueueDtcReport(v, dtc("00000000-0000-4000-8000-0000000000aa", atMillis = clockMillis - (RoomSyncStore.TELEMETRY_BACKLOG_DAYS + 1) * 86_400_000))
        assertTrue(store.dtcReports().none { it.report.clientReportId.endsWith("aa") })

        val other = car("Swift")
        store.enqueueDtcReport(other, dtc("00000000-0000-4000-8000-0000000000bb"))
        db.vehicleDao().delete(other)
        assertTrue(store.dtcReports().none { it.report.clientReportId.endsWith("bb") })

        db.syncDao().forgetServer()
        assertTrue(store.dtcReports().isEmpty())
    }

    @Test fun `an edit from another device updates the local vehicle, the first copy only records updatedAt`() = runBlocking {
        val v = car()
        val clientId = db.vehicleDao().get(v)!!.clientVehicleId
        trip(v)
        run()
        server.editVehicleElsewhere(clientId) { it.copy(nickname = "Family SX4", odometerKm = 42_000, fuelType = "diesel") }
        assertEquals(1, run().vehiclesEdited)
        val edited = db.vehicleDao().get(v)!!
        assertEquals("Family SX4", edited.name)
        assertEquals(42_000, edited.odometerKm)
        assertEquals("DIESEL", edited.fuel)
        assertEquals(0, run().vehiclesEdited)
    }

    @Test fun `vehicle create sends the real make and model, the name as nickname, and the clientVehicleId`() = runBlocking {
        val v = db.vehicleDao().insert(VehicleEntity(name = "Dad's car", maker = null, model = null, fuel = "CNG", displacementCc = null, odometerKm = null, createdAtMillis = 1))
        val create = store.vehicles().single { it.clientVehicleId == db.vehicleDao().get(v)!!.clientVehicleId }.create
        assertNull("unknown make is left out, never \"\"", create.make)
        assertNull(create.model)
        assertEquals("Dad's car", create.nickname)
        assertEquals("CNG presets are bi-fuel", "gasoline_cng", create.fuelType)
        assertEquals(db.vehicleDao().get(v)!!.clientVehicleId, create.clientVehicleId)
        car() // with make and model: those are sent as they are
        assertTrue(store.vehicles().any { it.create.make == "Maruti Suzuki" && it.create.nickname == "SX4" })
    }

    @Test fun `fuel flow goes only as the car's PID 5E, with MAF when read, never our computed flow`() {
        val computed = TripSampleEntity(tripId = 1, timestampMillis = 0, speedKph = 40.0, rpm = 1800.0, fuelFlowLph = 2.1, coolantC = 85.0)
        val none = RoomSyncStore.sampleDto(computed)
        assertNull(none.pids)
        assertNull(none.mafGps)
        val read = RoomSyncStore.sampleDto(computed.copy(fuelRatePidLph = 2.35, mafGps = 4.2))
        assertEquals(mapOf("0x5E" to 2.35), read.pids)
        assertEquals(4.2, read.mafGps!!, 0.0)
        val mafOnly = RoomSyncStore.sampleDto(computed.copy(mafGps = 4.2))
        assertNull("flow computed from MAF isn't sent", mafOnly.pids)
    }

    @Test fun `snapshots carry overSpeedCount and fuelCutCount, endReason only once completed (draft 9)`() = runBlocking {
        val v = car()
        val open = trip(v, ended = false)
        db.tripDao().updateTrip(open.copy(overspeedCount = 2))
        db.tripDao().insertEvents(listOf(
            TripEventEntity(tripId = open.id, timestampMillis = open.startedAtMillis + 1_000, type = "fuel_cut", value = 0.0),
            TripEventEntity(tripId = open.id, timestampMillis = open.startedAtMillis + 9_000, type = "fuel_cut", value = 0.0),
            TripEventEntity(tripId = open.id, timestampMillis = open.startedAtMillis + 5_000, type = "harsh_brake", value = -4.0),
        ))
        run() // vehicle server id
        val inProgress = store.uploadOf(open.clientTripId)!!
        assertEquals(2, inProgress.overSpeedCount)
        assertEquals(2, inProgress.fuelCutCount)
        assertNull("no endReason while in progress", inProgress.endReason)
        db.tripDao().updateTrip(db.tripDao().trip(open.id)!!.copy(endedAtMillis = open.lastSampleAtMillis, endReason = "engine_off"))
        assertEquals("engine_off", store.uploadOf(open.clientTripId)!!.endReason)
        db.tripDao().updateTrip(db.tripDao().trip(open.id)!!.copy(endReason = "demo"))
        assertEquals("other", store.uploadOf(open.clientTripId)!!.endReason)
    }

    @Test fun `a vehicle created as other before draft 9 is patched to its fuel type with its ETag`() = runBlocking {
        val v = db.vehicleDao().insert(VehicleEntity(name = "Celerio", maker = "Maruti Suzuki", model = "Celerio 1.0 CNG", fuel = "CNG", displacementCc = 998, odometerKm = null, createdAtMillis = 1))
        trip(v)
        val create = store.vehicles().single().create
        val created = server.createVehicle(create.copy(fuelType = "other"), "old") // as the app sent it before draft.9
        store.setVehicleServerId(create.clientVehicleId, created.id)
        server.patchFailures += ApiError.Http(412, null) // someone else's edit in between: fetch again
        assertEquals(1, run().vehiclesFuelPatched)
        assertEquals("gasoline_cng", server.vehicles.single().fuelType)
        assertEquals("CNG", db.vehicleDao().get(v)!!.fuel)
        assertEquals(0, run().vehiclesFuelPatched)
    }
}
