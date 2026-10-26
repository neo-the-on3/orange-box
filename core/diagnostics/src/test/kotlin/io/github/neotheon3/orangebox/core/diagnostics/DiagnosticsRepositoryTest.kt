package io.github.neotheon3.orangebox.core.diagnostics

import io.github.neotheon3.orangebox.core.database.DtcRecordEntity
import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.simulator.DtcList
import io.github.neotheon3.orangebox.core.simulator.SimulatedElm327Transport
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import io.github.neotheon3.orangebox.core.telemetry.InstantTelemetry
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.TripTotals
import io.github.neotheon3.orangebox.core.testing.FakeDtcHistoryRepository
import io.github.neotheon3.orangebox.core.testing.FakeObdConnection
import io.github.neotheon3.orangebox.core.testing.FakeTelemetrySource
import io.github.neotheon3.orangebox.core.testing.FakeVehicleRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scan and clear against the simulated ELM327 (the same one the debug Simulator drives). */
class DiagnosticsRepositoryTest {
    private val sim = SimulatorController(SimulatorState(engineOn = true))
    private val connection = FakeObdConnection()
    private val history = FakeDtcHistoryRepository()
    private val telemetry = FakeTelemetrySource()
    private val repo = ObdDiagnosticsRepository(connection, history, FakeVehicleRepository(), telemetry).apply { clock = { 42_000L } }

    private val sent = mutableListOf<String>()

    private suspend fun connect() {
        val sim327 = SimulatedElm327Transport(sim).also { it.open() }
        val counting = object : io.github.neotheon3.orangebox.core.obd.ObdTransport by sim327 {
            override suspend fun writeLine(line: String) { sent += line; sim327.writeLine(line) }
        }
        val session = Elm327Session(counting)
        val info = session.initialize()
        connection.session = session
        connection.state.value = ObdConnectionState.Connected(info)
    }

    @Test fun `not connected`() = runTest {
        assertEquals(ScanResult.NotConnected, repo.scan())
        assertEquals(ClearResult.NotConnected, repo.clear())
        assertNull(repo.latest.value)
    }

    @Test fun `injected codes come back grouped, with MIL, VIN and history`() = runTest {
        listOf("P0300", "P0420", "U0100").forEach { sim.injectDtc(it) }
        sim.addDtc("P0171", DtcList.PENDING)
        sim.addDtc("P0420", DtcList.PERMANENT)
        connect()
        val report = (repo.scan() as ScanResult.Success).report
        assertEquals(listOf("P0300", "P0420", "U0100"), report.codes.filter { it.status == DtcStatus.STORED }.map { it.code })
        assertEquals(listOf("P0171"), report.codes.filter { it.status == DtcStatus.PENDING }.map { it.code })
        assertEquals(listOf("P0420"), report.codes.filter { it.status == DtcStatus.PERMANENT }.map { it.code })
        assertEquals(setOf(DtcGroup.IGNITION, DtcGroup.AIR_FUEL, DtcGroup.NETWORK), report.faultedGroups)
        assertEquals(true, report.milOn)
        assertEquals(SimulatorState.VIN, report.vin)
        assertEquals(42_000L, report.readAtMillis)
        assertEquals(report, repo.latest.value)
        val (lists, _) = history.readings.single()
        assertEquals(listOf("P0300", "P0420", "U0100"), lists[DtcRecordEntity.KIND_STORED])
        assertEquals(listOf("P0420"), lists[DtcRecordEntity.KIND_PERMANENT])
    }

    @Test fun `unsupported permanent codes are not a failure, and are skipped on the next scan (#196)`() = runTest {
        sim.injectDtc("P0300")
        sim.update { it.copy(permanentUnsupported = true) }
        connect()
        val report = (repo.scan() as ScanResult.Success).report
        assertEquals(setOf(DtcStatus.PERMANENT), report.unsupported)
        assertTrue(report.unreadable.isEmpty())
        assertEquals(listOf("P0300"), report.codes.map { it.code })
        assertEquals(1, sent.count { it == "0A" })
        val again = (repo.scan() as ScanResult.Success).report
        assertEquals(setOf(DtcStatus.PERMANENT), again.unsupported)
        assertEquals("cached per vehicle: 0A not sent again", 1, sent.count { it == "0A" })
        assertEquals(2, sent.count { it == "03" })
    }

    @Test fun `MIL toggle alone is a trouble without codes`() = runTest {
        sim.update { it.copy(milOn = true) }
        connect()
        val report = (repo.scan() as ScanResult.Success).report
        assertTrue(report.codes.isEmpty())
        assertTrue(report.hasTroubles)
    }

    @Test fun `clear sends mode 04, resets the MIL, records it and reads again`() = runTest {
        sim.injectDtc("P0300")
        sim.addDtc("P0420", DtcList.PERMANENT)
        connect()
        repo.scan()
        val result = repo.clear() as ClearResult.Cleared
        assertTrue(sim.state.value.dtcs.isEmpty())
        assertFalse(sim.state.value.milOn)
        assertEquals(listOf(42_000L), history.cleared)
        val after = result.report!!
        assertEquals(false, after.milOn)
        assertEquals(listOf(TroubleCode("P0420", DtcStatus.PERMANENT)), after.codes) // permanent stays
        assertFalse(after.hasClearableCodes)
        assertEquals(after, repo.latest.value)
    }

    @Test fun `reads and clears are handed to the report sink, unreadable lists left out (#67)`() = runTest {
        val readings = mutableListOf<DtcReading>()
        val reporting = ObdDiagnosticsRepository(connection, history, FakeVehicleRepository(), telemetry, DtcReportSink { readings += it })
            .apply { clock = { 42_000L } }
        sim.injectDtc("P0300")
        sim.addDtc("P0171", DtcList.PENDING)
        sim.update { it.copy(permanentUnsupported = true) }
        connect()
        reporting.scan()
        val read = readings.single()
        assertEquals(DtcReading.Kind.READ, read.kind)
        assertEquals(42_000L, read.atMillis)
        assertEquals(true, read.milOn)
        assertEquals(SimulatorState.VIN, read.vin)
        assertEquals(listOf("P0300"), read.stored)
        assertEquals(listOf("P0171"), read.pending)
        assertNull("unsupported list is left out", read.permanent)

        reporting.clear() as ClearResult.Cleared
        val (cleared, after) = readings.drop(1)
        assertEquals(DtcReading.Kind.CLEARED, cleared.kind)
        assertEquals(listOf("P0300"), cleared.stored)
        assertEquals(listOf("P0171"), cleared.pending)
        assertEquals(DtcReading.Kind.READ, after.kind)
        assertEquals(emptyList<String>(), after.stored)
    }

    @Test fun `a failing sink doesn't fail the scan`() = runTest {
        val failing = ObdDiagnosticsRepository(connection, history, FakeVehicleRepository(), telemetry, DtcReportSink { error("disk full") })
        sim.injectDtc("P0300")
        connect()
        assertTrue(failing.scan() is ScanResult.Success)
    }

    @Test fun `clear is blocked while moving`() = runTest {
        sim.injectDtc("P0300")
        connect()
        telemetry.latest.value = TelemetrySnapshot(
            0, InstantTelemetry(30.0, 1500.0, 90.0, 50.0, 14.0, 2.0, 7.0, 0.0, false, false, false, false, emptySet()), TripTotals(),
        )
        assertEquals(ClearResult.WhileMoving, repo.clear())
        assertEquals(listOf("P0300"), sim.state.value.dtcs)
        assertTrue(history.cleared.isEmpty())
    }

    @Test fun `a dropped link fails the scan instead of throwing`() = runTest {
        connect()
        sim.update { it.copy(connectionFailure = true) }
        assertTrue(repo.scan() is ScanResult.Failed)
        assertFalse(repo.busy.value)
    }
}
