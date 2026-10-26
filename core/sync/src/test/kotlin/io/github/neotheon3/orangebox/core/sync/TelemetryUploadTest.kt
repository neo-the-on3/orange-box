package io.github.neotheon3.orangebox.core.sync

import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.ProblemDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Telemetry batches (#67): `POST /v1/telemetry/batches` as docs/backend/openapi-v2.yaml and REQUIREMENTS §7 define it. */
class TelemetryUploadTest {
    private var now = Instant.parse("2026-10-26T03:00:00Z")
    private val server = FakeBackendApi(clock = { now.also { now = now.plusMillis(1) } })
    private val phone = InMemorySyncStore()
    private val engine = SyncEngine(server, phone)

    private fun problem(status: Int, code: String) = ApiError.Http(status, ProblemDto("about:blank", code, status, code = code, requestId = "r"))

    @Test fun `samples and events of a finished trip go up after its summary, in batches within the limits`() = runTest {
        val trip = phone.record(phone.addVehicle())
        phone.addTelemetry(trip, sampleCount = 12_000, eventCount = 3)
        val r = engine.run()

        assertEquals(3, r.telemetryBatches) // 5,000 + 5,000 + 2,000
        assertEquals(12_000, r.telemetrySamples)
        assertEquals(3, r.telemetryEvents)
        assertEquals(12_000, server.telemetry.getValue(trip).samples.size)
        val firstTelemetry = server.calls.indexOf("POST /v1/telemetry/batches")
        assertTrue("trip summary first", server.calls.indexOf("POST /v1/trips/batch") in 0 until firstTelemetry)
        assertNull(phone.pendingTelemetry)
        assertEquals(0, engine.run().telemetryBatches)
    }

    @Test fun `unfinished, remote and never-uploaded trips send no telemetry`() = runTest {
        val car = phone.addVehicle()
        val open = phone.record(car, completed = false)
        phone.addTelemetry(open, 10)
        engine.run()
        assertTrue(server.telemetry.isEmpty())
        val remote = phone.record(car)
        phone.trips[remote] = phone.trips.getValue(remote).copy(remoteOrigin = true, uploadedBefore = true, serverId = "x")
        phone.addTelemetry(remote, 10)
        engine.run()
        assertTrue(server.telemetry.isEmpty())
    }

    @Test fun `a retried batch reuses its Idempotency-Key and clientBatchId, even across runs`() = runTest {
        val trip = phone.record(phone.addVehicle())
        phone.addTelemetry(trip, 10, 2)
        server.failNextTelemetry(FakeBackendApi.unavailable(5))
        try {
            engine.run()
            fail("503 propagates for the worker's backoff")
        } catch (e: ApiError.Http) {
            assertEquals(503, e.status)
        }
        val pending = phone.pendingTelemetry
        assertNotNull("kept for the next run (survives process death in Room)", pending)
        engine.run()
        val (firstKey, firstBatch) = server.telemetryRequests[0]
        val (retryKey, retryBatch) = server.telemetryRequests[1]
        assertEquals(pending!!.idempotencyKey, firstKey)
        assertEquals(firstKey, retryKey)
        assertEquals(firstBatch, retryBatch)
        assertNull(phone.pendingTelemetry)
        assertEquals(10, server.telemetry.getValue(trip).samples.size)
    }

    @Test fun `413 splits the batch and the smaller size is kept for the run`() = runTest {
        val trip = phone.record(phone.addVehicle())
        phone.addTelemetry(trip, 4_000, 10)
        server.maxTelemetryItems = 1_500
        val r = engine.run()
        assertEquals(4_000, server.telemetry.getValue(trip).samples.size)
        assertEquals(10, server.telemetry.getValue(trip).events.size)
        assertTrue(r.telemetryBatches >= 3)
        assertEquals(0, r.telemetryRejected)
    }

    @Test fun `413 on a single item marks that trip failed instead of looping`() = runTest {
        val trip = phone.record(phone.addVehicle())
        phone.addTelemetry(trip, 3)
        server.maxTelemetryItems = 0
        val r = engine.run()
        assertEquals(1, r.telemetryRejected)
        assertEquals(SyncEngine.CODE_PAYLOAD_TOO_LARGE, phone.telemetryDone[trip])
        assertEquals(0, engine.run().telemetryBatches)
    }

    @Test fun `422 marks the trip's telemetry failed and is not retried`() = runTest {
        val trip = phone.record(phone.addVehicle())
        val other = phone.record(phone.vehicles.keys.first())
        phone.addTelemetry(trip, 3)
        phone.addTelemetry(other, 2)
        engine.run() // summaries and telemetry of both
        phone.addTelemetry(trip, 2, from = Instant.parse("2026-10-20T12:00:00Z"))
        phone.addTelemetry(other, 2, from = Instant.parse("2026-10-20T12:00:00Z"))
        server.failNextTelemetry(problem(422, "validation_failed"))
        val r = engine.run()
        assertEquals(1, r.telemetryRejected)
        assertEquals("validation_failed", phone.telemetryDone[trip])
        assertEquals("the other trip still uploads", 4, server.telemetry.getValue(other).samples.size)
        assertNull(phone.pendingTelemetry)
    }

    @Test fun `trip_deleted drops the rest of that trip's telemetry`() = runTest {
        val trip = phone.record(phone.addVehicle())
        engine.run()
        server.deleteTripElsewhere(trip)
        phone.addTelemetry(trip, 6_000)
        val r = engine.run()
        // The deletions walk removes the trip locally first; if it hadn't, the ack would stop the upload.
        assertTrue(r.telemetryBatches <= 1)
        assertTrue(server.telemetry[trip] == null)
    }

    @Test fun `trip_deleted ack marks the trip done`() = runTest {
        val trip = phone.record(phone.addVehicle())
        phone.addTelemetry(trip, 6_000)
        val tombstoning = object : io.github.neotheon3.orangebox.core.network.BackendApi by server {
            override suspend fun uploadTelemetry(batch: io.github.neotheon3.orangebox.core.network.TelemetryBatchDto, idempotencyKey: String) =
                io.github.neotheon3.orangebox.core.network.TelemetryBatchAckDto(batch.clientBatchId, "trip_deleted", 0, 0)
        }
        val r = SyncEngine(tombstoning, phone).run()
        assertEquals(1, r.telemetryBatches)
        assertEquals("trip_deleted", phone.telemetryDone[trip])
    }

    @Test fun `404 vehicle_not_found clears the vehicle's server id and stops telemetry for the run`() = runTest {
        val car = phone.addVehicle()
        val trip = phone.record(car)
        phone.addTelemetry(trip, 3)
        engine.run()
        phone.addTelemetry(trip, 3, from = Instant.parse("2026-10-20T12:00:00Z"))
        server.failNextTelemetry(problem(404, ApiError.CODE_VEHICLE_NOT_FOUND))
        engine.run()
        assertNull(phone.vehicles.getValue(car).serverId)
        assertNull(phone.pendingTelemetry)
    }
}
