package io.github.neotheon3.orangebox.core.diagnostics

import io.github.neotheon3.orangebox.core.database.DtcRecordEntity
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.simulator.SimulatedElm327Transport
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import io.github.neotheon3.orangebox.core.testing.FakeDtcHistoryRepository
import io.github.neotheon3.orangebox.core.testing.FakeObdConnection
import io.github.neotheon3.orangebox.core.testing.FakeTelemetrySource
import io.github.neotheon3.orangebox.core.testing.FakeVehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosingPromptControllerTest {
    private class ScriptedDiagnostics(var next: ScanResult) : DiagnosticsRepository {
        var scans = 0
        override val latest = MutableStateFlow<DiagnosticsReport?>(null)
        override val busy = MutableStateFlow(false)
        override val history = emptyFlow<List<DtcRecordEntity>>()
        override suspend fun scan(): ScanResult { scans++; return next }
        override suspend fun clear(): ClearResult = ClearResult.NotConnected
    }

    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 12.6))
    private fun report(vararg codes: String, mil: Boolean = codes.isNotEmpty()) =
        ScanResult.Success(DiagnosticsReport(0, codes.map { TroubleCode(it, DtcStatus.STORED) }, mil, null))

    private fun TestScope.setUp(result: ScanResult): Triple<FakeObdConnection, ScriptedDiagnostics, DiagnosingPromptController> {
        val connection = FakeObdConnection()
        val diagnostics = ScriptedDiagnostics(result)
        val controller = DiagnosingPromptController(backgroundScope, connection, diagnostics).apply { install() }
        runCurrent()
        return Triple(connection, diagnostics, controller)
    }

    @Test fun `no troubles shows the sweep, then auto-dismisses`() = runTest {
        val (connection, _, controller) = setUp(report())
        connection.state.value = connected
        runCurrent()
        assertEquals(DiagnosingPrompt.Scanning, controller.prompt.value)
        advanceTimeBy(DiagnosingPromptController.MIN_SCAN_MILLIS + 1)
        assertEquals(DiagnosingPrompt.NoTroubles, controller.prompt.value)
        advanceTimeBy(DiagnosingPromptController.NO_TROUBLES_MILLIS + 1)
        assertEquals(DiagnosingPrompt.Hidden, controller.prompt.value)
    }

    @Test fun `codes give a warning that stays until dismissed`() = runTest {
        val (connection, _, controller) = setUp(report("P0300", "P0420", "U0100"))
        connection.state.value = connected
        advanceTimeBy(10_000)
        assertEquals(DiagnosingPrompt.Warning(3, milOn = true), controller.prompt.value)
        controller.dismiss()
        assertEquals(DiagnosingPrompt.Hidden, controller.prompt.value)
    }

    @Test fun `the MIL toggle alone warns`() = runTest {
        val (connection, _, controller) = setUp(report(mil = true))
        connection.state.value = connected
        advanceTimeBy(10_000)
        assertEquals(DiagnosingPrompt.Warning(0, milOn = true), controller.prompt.value)
    }

    @Test fun `shown once per drive, a link drop does not prompt again, a new drive does`() = runTest {
        val (connection, diagnostics, controller) = setUp(report("P0300"))
        connection.state.value = connected
        advanceTimeBy(10_000)
        controller.dismiss()
        connection.state.value = ObdConnectionState.LinkLost
        runCurrent()
        connection.state.value = connected
        advanceTimeBy(10_000)
        assertEquals(1, diagnostics.scans)
        assertEquals(DiagnosingPrompt.Hidden, controller.prompt.value)
        connection.state.value = ObdConnectionState.Idle // drive ended
        runCurrent()
        connection.state.value = connected
        advanceTimeBy(10_000)
        assertEquals(2, diagnostics.scans)
        assertEquals(DiagnosingPrompt.Warning(1, milOn = true), controller.prompt.value)
    }

    @Test fun `dismissing while scanning keeps it hidden`() = runTest {
        val (connection, _, controller) = setUp(report("P0300"))
        connection.state.value = connected
        runCurrent()
        controller.dismiss()
        advanceTimeBy(10_000)
        assertEquals(DiagnosingPrompt.Hidden, controller.prompt.value)
    }

    @Test fun `a failed scan hides the prompt`() = runTest {
        val (connection, _, controller) = setUp(ScanResult.Failed(ObdError.Timeout().describe()))
        connection.state.value = connected
        advanceTimeBy(10_000)
        assertEquals(DiagnosingPrompt.Hidden, controller.prompt.value)
    }

    @Test fun `simulator MIL toggle drives the prompt end to end`() = runTest {
        val sim = SimulatorController(SimulatorState(engineOn = true, milOn = true))
        val connection = FakeObdConnection()
        val repo = ObdDiagnosticsRepository(connection, FakeDtcHistoryRepository(), FakeVehicleRepository(), FakeTelemetrySource())
        val controller = DiagnosingPromptController(backgroundScope, connection, repo).apply { install() }
        val session = Elm327Session(SimulatedElm327Transport(sim).also { it.open() })
        val info = session.initialize()
        connection.session = session
        connection.state.value = ObdConnectionState.Connected(info)
        advanceTimeBy(DiagnosingPromptController.MIN_SCAN_MILLIS + 1)
        assertEquals(DiagnosingPrompt.Warning(0, milOn = true), controller.prompt.value)
        controller.dismiss()

        // Next drive with the MIL off: No Troubles.
        sim.update { it.copy(milOn = false) }
        connection.state.value = ObdConnectionState.Idle
        runCurrent()
        connection.state.value = ObdConnectionState.Connected(info)
        advanceTimeBy(DiagnosingPromptController.MIN_SCAN_MILLIS + 1)
        assertEquals(DiagnosingPrompt.NoTroubles, controller.prompt.value)
    }
}
