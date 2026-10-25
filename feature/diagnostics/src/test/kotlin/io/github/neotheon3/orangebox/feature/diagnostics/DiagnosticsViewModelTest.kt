package io.github.neotheon3.orangebox.feature.diagnostics

import io.github.neotheon3.orangebox.core.diagnostics.DtcGroup
import io.github.neotheon3.orangebox.core.diagnostics.ObdDiagnosticsRepository
import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.simulator.SimulatedElm327Transport
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import io.github.neotheon3.orangebox.core.testing.FakeDtcHistoryRepository
import io.github.neotheon3.orangebox.core.testing.FakeObdConnection
import io.github.neotheon3.orangebox.core.testing.FakeTelemetrySource
import io.github.neotheon3.orangebox.core.testing.FakeVehicleRepository
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** End to end against the simulated ELM327: read on open, confirm → mode 04 → read again (#49). */
class DiagnosticsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val sim = SimulatorController(SimulatorState(engineOn = true))
    private val connection = FakeObdConnection()
    private val history = FakeDtcHistoryRepository()
    private val repo = ObdDiagnosticsRepository(connection, history, FakeVehicleRepository(), FakeTelemetrySource())

    private suspend fun connect() {
        val session = Elm327Session(SimulatedElm327Transport(sim).also { it.open() })
        val info = session.initialize()
        connection.session = session
        connection.state.value = ObdConnectionState.Connected(info)
    }

    @Test fun `reads on connect and clears after confirmation`() = runTest {
        listOf("P0300", "P0420", "U0100").forEach { sim.injectDtc(it) }
        val vm = DiagnosticsViewModel(repo, connection, FakeTelemetrySource())
        backgroundScope.launch(main.testDispatcher) { vm.state.collect {} }
        assertNull(vm.state.value.report)
        connect()
        val report = vm.state.value.report!!
        assertEquals(setOf(DtcGroup.IGNITION, DtcGroup.AIR_FUEL, DtcGroup.NETWORK), report.faultedGroups)
        assertTrue(vm.state.value.connected)

        vm.clear()
        assertTrue(sim.state.value.dtcs.isEmpty())
        assertFalse(sim.state.value.milOn)
        assertEquals(DiagnosticsNotice.CLEARED, vm.state.value.notice)
        assertTrue(vm.state.value.report!!.codes.isEmpty())
        assertEquals(1, history.cleared.size)
    }

    @Test fun `refresh while disconnected says so`() = runTest {
        val vm = DiagnosticsViewModel(repo, connection, FakeTelemetrySource())
        backgroundScope.launch(main.testDispatcher) { vm.state.collect {} }
        vm.refresh()
        assertEquals(DiagnosticsNotice.NOT_CONNECTED, vm.state.value.notice)
        vm.clear()
        assertEquals(DiagnosticsNotice.NOT_CONNECTED, vm.state.value.notice)
    }
}
