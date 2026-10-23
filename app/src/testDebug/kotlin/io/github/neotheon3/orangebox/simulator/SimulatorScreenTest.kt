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
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvent
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
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
        override fun injectDtc(code: String) = controller.injectDtc(code).also { ui = ui.copy(sim = controller.state.value) }
        override fun clearDtcs() { controller.clearDtcs(); ui = ui.copy(sim = controller.state.value) }
        override fun setBackendMode(mode: BackendMode) { ui = ui.copy(backendMode = mode) }
        override fun reset() { controller.reset(); ui = ui.copy(sim = controller.state.value); calls += "reset" }
    }

    private val sim get() = controller.state.value

    @Before fun setUp() {
        compose.setContent { OrangeBoxTheme { SimulatorScreen(ui, actions, onBack = { calls += "back" }) } }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun slide(tag: String, v: Float) =
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(v) }

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
    @Test fun `invalid dtc is rejected`() {
        compose.onNodeWithTag("sim_dtc_input").performScrollTo().performTextReplacement("nope")
        click("sim_dtc_inject")
        assertTrue(sim.dtcs.isEmpty())
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
