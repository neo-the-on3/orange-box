package io.github.neotheon3.orangebox.simulator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvent
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import io.github.neotheon3.orangebox.core.obd.VinDecoder
import io.github.neotheon3.orangebox.core.simulator.DtcList
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One assertion per Simulator control, against a recording [SimulatorActions]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimulatorScreenTest {
    @get:Rule val compose = createComposeRule()

    private val controller = SimulatorController()
    private var ui by mutableStateOf(SimulatorUiState())
    private val calls = mutableListOf<String>()

    private val actions = object : SimulatorActions {
        override fun setUseSimulatedDongle(on: Boolean) { calls += "use=$on"; ui = ui.copy(simulatedSelected = on) }
        override fun update(transform: (SimulatorState) -> SimulatorState) { controller.update(transform); ui = ui.copy(sim = controller.state.value) }
        override fun linkConnected() { calls += "link=connected" }
        override fun linkDisconnected() { calls += "link=disconnected" }
        override fun injectDtc(code: String, list: DtcList) = controller.addDtc(code, list).also { ui = ui.copy(sim = controller.state.value) }
        override fun setVin(vin: String) { controller.setVin(vin); ui = ui.copy(sim = controller.state.value) }
        override fun readDiagnostics() {
            calls += "read"
            ui = ui.copy(readout = DiagnosticsReadout(listOf("P0133"), listOf("P0300"), emptyList(), VinDecoder.Result.Valid(SimulatorState.VIN, true)))
        }
        override fun clearDtcs() { controller.clearDtcs(); ui = ui.copy(sim = controller.state.value) }
        override fun setBackendMode(mode: BackendMode) { ui = ui.copy(backendMode = mode) }
        override fun startScript(id: String) { calls += "script=$id"; controller.update { it.copy(runningScript = id) }; ui = ui.copy(sim = controller.state.value) }
        override fun stopScript() { calls += "stop"; controller.update { it.copy(runningScript = null) }; ui = ui.copy(sim = controller.state.value) }
        override fun injectImpact() { calls += "impact" }
        override fun reset() { controller.reset(); ui = ui.copy(sim = controller.state.value); calls += "reset" }
        override fun armFakeFailure(scenario: FakeBackendApi.Scenario) { calls += "arm=$scenario"; ui = ui.copy(fakeArmed = scenario) }
        override fun setFakeMemberFeatures(on: Boolean) { calls += "member=$on"; ui = ui.copy(fakeMemberFeatures = on) }
    }

    private val sim get() = controller.state.value

    @Before fun setUp() {
        compose.setContent { OrangeBoxTheme { SimulatorScreen(ui, actions, onBack = { calls += "back" }) } }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun slide(tag: String, v: Float) =
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(v) }

    @Test fun `fake server failure scenarios`() {
        click("sim_fake_412"); click("sim_fake_500"); click("sim_fake_503")
        assertEquals(listOf("arm=CONFLICT_412", "arm=PROBLEM_500", "arm=UNAVAILABLE_503"), calls)
        compose.onNodeWithTag("sim_fake_armed").performScrollTo().assertTextContains("UNAVAILABLE_503", substring = true)
    }

    @Test fun `fake member features switch`() { click("sim_fake_member_features"); assertEquals(listOf("member=false"), calls) }

    @Test fun `inject impact`() { click("sim_impact"); assertEquals(listOf("impact"), calls) }
    @Test fun `use simulated dongle`() { click("sim_use_simulated"); assertEquals(listOf("use=true"), calls) }
    @Test fun `paired toggle`() { click("sim_paired"); assertFalse(sim.paired) }
    @Test fun `bt connected and disconnected buttons`() {
        click("sim_link_connected"); click("sim_link_disconnected")
        assertEquals(listOf("link=connected", "link=disconnected"), calls)
    }
    @Test fun `engine toggle`() { click("sim_engine"); assertFalse(sim.engineOn) }
    @Test fun `speed slider`() { slide("sim_speed", 87f); assertEquals(87, sim.speedKph) }
    @Test fun `rpm slider`() { slide("sim_rpm", 2450f); assertEquals(2450, sim.rpm) }
    @Test fun `coolant slider`() { slide("sim_coolant", 92f); assertEquals(92, sim.coolantC) }
    @Test fun `fuel slider`() { slide("sim_fuel", 40f); assertEquals(40, sim.fuelPercent) }
    @Test fun `dtc inject, mil and clear`() {
        compose.onNodeWithTag("sim_dtc_input").performScrollTo().performTextReplacement("P0420")
        click("sim_dtc_inject")
        assertEquals(listOf("P0420"), sim.dtcs); assertTrue(sim.milOn)
        compose.onNodeWithTag("sim_dtc_list").assertTextContains("P0420", substring = true)
        click("sim_mil"); assertFalse(sim.milOn)
        click("sim_mil"); assertTrue(sim.milOn)
        click("sim_dtc_clear")
        assertTrue(sim.dtcs.isEmpty()); assertFalse(sim.milOn)
    }
    @Test fun `demo button injects the M6 acceptance codes`() {
        click("sim_dtc_demo")
        assertEquals(listOf("P0300", "P0420", "U0100"), sim.dtcs); assertTrue(sim.milOn)
    }
    @Test fun `pending and permanent dtc injection`() {
        click("sim_dtc_kind_pending")
        compose.onNodeWithTag("sim_dtc_input").performScrollTo().performTextReplacement("P0300")
        click("sim_dtc_inject")
        click("sim_dtc_kind_permanent")
        compose.onNodeWithTag("sim_dtc_input").performScrollTo().performTextReplacement("P0420")
        click("sim_dtc_inject")
        assertEquals(listOf("P0300"), sim.pendingDtcs)
        assertEquals(listOf("P0420"), sim.permanentDtcs)
        assertTrue(sim.dtcs.isEmpty())
        compose.onNodeWithTag("sim_dtc_other_lists").assertTextContains("Pending: P0300   Permanent: P0420")
    }

    @Test fun `vin can be set`() {
        compose.onNodeWithTag("sim_vin_input").performScrollTo().performTextReplacement("1M8GDM9AXKP042788")
        click("sim_vin_set")
        assertEquals("1M8GDM9AXKP042788", sim.vin)
    }

    @Test fun `app readout shows DTC lists and VIN`() {
        click("sim_readout_read")
        assertEquals(listOf("read"), calls)
        compose.onNodeWithTag("sim_readout_stored").performScrollTo().assertTextContains("Stored (03): P0133")
        compose.onNodeWithTag("sim_readout_pending").assertTextContains("Pending (07): P0300")
        compose.onNodeWithTag("sim_readout_permanent").assertTextContains("Permanent (0A): none")
        compose.onNodeWithTag("sim_readout_vin").assertTextContains(SimulatorState.VIN, substring = true)
        compose.onNodeWithTag("sim_readout_telemetry").assertTextContains("not connected", substring = true)
    }

    @Test fun `app readout shows cumulative eco seconds for the trip`() {
        val instant = io.github.neotheon3.orangebox.core.telemetry.InstantTelemetry(
            speedKph = 0.0, rpm = 800.0, coolantC = 90.0, fuelLevelPercent = 50.0, voltage = 14.0,
            fuelFlowLph = 0.8, economyLPer100Km = null, accelMps2 = 0.0,
            fuelCut = false, idling = true, eco = false, overspeed = false, warnings = emptySet(),
        )
        val trip = io.github.neotheon3.orangebox.core.telemetry.TripTotals(ecoS = 91.0, idleS = 31.5, fuelCutS = 13.5)
        ui = ui.copy(telemetry = io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot(0L, instant, trip))
        compose.onNodeWithTag("sim_readout_eco").performScrollTo().assertTextContains("Eco driving (trip): 91.0 s")
        compose.onNodeWithTag("sim_readout_times").assertTextContains("Idle 31.5 s · fuel cut 13.5 s")
        assertEquals("Eco driving (trip): 19.0 s", ecoReadout(19.0))
    }

    @Test fun `invalid dtc is rejected with supporting text`() {
        compose.onNodeWithTag("sim_dtc_input").performScrollTo().performTextReplacement("XYZ12")
        click("sim_dtc_inject")
        assertTrue(sim.dtcs.isEmpty())
        compose.onNodeWithTag("sim_dtc_error", useUnmergedTree = true).assertTextContains("Invalid code", substring = true)
    }
    @Test fun `failure toggles show the banner`() {
        click("sim_fail_connection"); assertTrue("connection", sim.connectionFailure)
        click("sim_fail_protocol"); assertTrue("protocol", sim.unsupportedProtocol)
        click("sim_slow"); assertTrue("slow", sim.slowResponses)
        click("sim_garbled"); assertTrue("garbled", sim.garbledResponses)
        compose.onNodeWithTag("sim_active_failures").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Active failures:", substring = true).assertTextContains("Garbled", substring = true)
    }
    @Test fun `backend mode radios`() {
        click("sim_backend_fake"); assertEquals(BackendMode.FAKE, ui.backendMode)
        click("sim_backend_remote"); assertEquals(BackendMode.REMOTE, ui.backendMode)
        click("sim_backend_off"); assertEquals(BackendMode.OFF, ui.backendMode)
    }
    @Test fun `reset restores defaults`() {
        click("sim_garbled"); slide("sim_speed", 120f)
        click("sim_reset")
        assertEquals(SimulatorState(), sim)
    }
    @Test fun `throttle slider`() { slide("sim_throttle", 35f); assertEquals(35, sim.throttlePercent) }
    @Test fun `drive scripts start and stop`() {
        click("sim_script_city")
        compose.onNodeWithTag("sim_script_status").assertTextContains("City", substring = true)
        click("sim_script_highway"); click("sim_script_idle"); click("sim_script_harsh")
        click("sim_script_stop")
        compose.onNodeWithTag("sim_script_status").assertTextContains("No script running")
        assertEquals(listOf("script=city", "script=highway", "script=idle", "script=harsh", "stop"), calls)
    }
    @Test fun `garbled ATDPN toggle`() {
        click("sim_garbled_atdpn")
        assertTrue(sim.garbledProtocolReplies)
        compose.onNodeWithText("Active failures:", substring = true).assertTextContains("Garbled ATDPN", substring = true)
    }
    @Test fun `back`() { compose.onNodeWithTag("sim_back").performClick(); assertEquals(listOf("back"), calls) }

    @Test fun `status panel shows attempt, countdown, error and link event`() {
        ui = ui.copy(
            connection = ObdConnectionState.WaitingToRetry(3, 5, 8, ObdError.Io("refused")),
            lastLinkEvent = DongleLinkEvent.Disconnected(DongleLinkEvent.SIMULATED_ADDRESS),
        )
        compose.onNodeWithTag("sim_status_state").assertTextContains("Waiting to retry", substring = true)
        compose.onNodeWithTag("sim_status_attempt").assertTextContains("3/5", substring = true)
        compose.onNodeWithTag("sim_status_countdown").assertTextContains("8 s", substring = true)
        compose.onNodeWithTag("sim_status_error").assertTextContains("refused", substring = true)
        compose.onNodeWithTag("sim_status_link").assertTextContains("disconnected", substring = true)
    }
}
