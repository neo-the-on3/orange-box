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

    private suspend fun connect() {
        val session = Elm327Session(SimulatedElm327Transport(sim).also { it.open() })
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
