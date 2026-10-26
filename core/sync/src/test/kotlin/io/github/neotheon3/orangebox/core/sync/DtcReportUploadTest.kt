package io.github.neotheon3.orangebox.core.sync

import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.network.DtcReportCreateDto
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.ProblemDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** DTC reads and clears (#67): `POST /v1/vehicles/{vehicleId}/dtc-reports` as docs/backend/openapi-v2.yaml defines it. */
class DtcReportUploadTest {
    private val server = FakeBackendApi(clock = { Instant.parse("2026-10-26T03:00:00Z") })
    private val phone = InMemorySyncStore()
    private val engine = SyncEngine(server, phone)

    private fun problem(status: Int, code: String) = ApiError.Http(status, ProblemDto("about:blank", code, status, code = code, requestId = "r"))

    private fun queue(vehicle: String, at: String = "2026-10-25T08:00:00Z", kind: String = DtcReportCreateDto.KIND_READ, stored: List<String> = listOf("P0300")): String {
        val id = UUID.randomUUID().toString()
        phone.dtcQueue += QueuedDtcReport(vehicle, DtcReportCreateDto(id, kind, Instant.parse(at), mil = stored.isNotEmpty(), stored = stored))
        return id
    }

    @Test fun `queued reports go up oldest first, creating the vehicle first, with the report id as key`() = runTest {
        val car = phone.addVehicle()
        val later = queue(car, at = "2026-10-25T09:00:00Z", kind = DtcReportCreateDto.KIND_CLEARED)
        val first = queue(car, at = "2026-10-25T08:00:00Z")
        val r = engine.run()

        assertEquals(1, r.vehiclesCreated) // a vehicle with only DTC reports is created too
        assertEquals(2, r.dtcReports)
        assertEquals(listOf(first, later), server.dtcReports.map { it.clientReportId })
        val serverId = phone.vehicles.getValue(car).serverId!!
        assertEquals(listOf(serverId to first, serverId to later), server.dtcReportRequests)
        assertTrue(phone.dtcQueue.isEmpty())
        assertEquals(0, engine.run().dtcReports)
    }

    @Test fun `a lost response is resent with the same key and replays`() = runTest {
        val car = phone.addVehicle()
        val id = queue(car)
        server.failNextDtcReport(ApiError.Network(java.io.IOException("reset")))
        try {
            engine.run(); fail("network errors propagate for the worker's backoff")
        } catch (_: ApiError.Network) {
        }
        assertEquals(1, phone.dtcQueue.size)
        assertEquals(1, engine.run().dtcReports)
        assertEquals(listOf(id, id), server.dtcReportRequests.map { it.second })
        assertEquals(1, server.dtcReports.size)
    }

    @Test fun `retryable errors keep the report`() = runTest {
        val car = phone.addVehicle()
        queue(car)
        server.failNextDtcReport(problem(503, "unavailable"))
        runCatching { engine.run() }
        assertEquals(1, phone.dtcQueue.size)
    }

    @Test fun `422 and 413 drop the report, others still go up`() = runTest {
        val car = phone.addVehicle()
        queue(car, at = "2026-10-25T08:00:00Z")
        queue(car, at = "2026-10-25T08:01:00Z")
        val kept = queue(car, at = "2026-10-25T08:02:00Z")
        server.failNextDtcReport(problem(422, "validation_failed"), problem(413, "batch_too_large"))
        val r = engine.run()
        assertEquals(2, r.dtcReportsDropped)
        assertEquals(1, r.dtcReports)
        assertEquals(listOf(kept), server.dtcReports.map { it.clientReportId })
        assertTrue(phone.dtcQueue.isEmpty())
    }

    @Test fun `404 forgets the vehicle's server id and the report waits for the next run`() = runTest {
        val car = phone.addVehicle()
        engine.run()
        phone.vehicles[car] = phone.vehicles.getValue(car).copy(serverId = "veh_gone")
        queue(car)
        queue(car, at = "2026-10-25T08:05:00Z")
        val r = engine.run()
        assertEquals(0, r.dtcReports)
        assertEquals(1, server.dtcReportRequests.size) // the second report waits too
        assertEquals(null, phone.vehicles.getValue(car).serverId)
        assertEquals(2, engine.run().dtcReports) // vehicle re-created (200 replay by clientVehicleId), then both go up
    }

    @Test fun `reports of a vehicle deleted on the server are dropped`() = runTest {
        val car = phone.addVehicle()
        phone.vehicles[car] = phone.vehicles.getValue(car).copy(goneOnServer = true)
        queue(car)
        queue("not-a-local-vehicle")
        val r = engine.run()
        assertEquals(2, r.dtcReportsDropped)
        assertTrue(server.dtcReportRequests.isEmpty())
        assertTrue(phone.dtcQueue.isEmpty())
    }

    @Test fun `501 keeps every report queued and pauses the step`() = runTest {
        var clock = 0L
        val engine = SyncEngine(server, phone, clock = { clock })
        val car = phone.addVehicle()
        queue(car)
        queue(car, at = "2026-10-25T08:05:00Z")
        server.failNextDtcReport(problem(501, "not_implemented"))
        val r = engine.run()
        assertEquals(setOf(SyncEngine.STEP_DTC_REPORTS), r.notImplemented)
        assertEquals(0, r.dtcReportsDropped)
        assertEquals(2, phone.dtcQueue.size)
        assertEquals(1, server.dtcReportRequests.size)
        engine.run()
        assertEquals("paused", 1, server.dtcReportRequests.size)
        clock = SyncEngine.NOT_IMPLEMENTED_RETRY_MILLIS
        assertEquals(2, engine.run().dtcReports)
        assertTrue(phone.dtcQueue.isEmpty())
    }
}
