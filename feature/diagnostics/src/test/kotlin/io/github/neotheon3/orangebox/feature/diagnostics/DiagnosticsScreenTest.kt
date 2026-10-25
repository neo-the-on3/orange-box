package io.github.neotheon3.orangebox.feature.diagnostics

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.neotheon3.orangebox.core.diagnostics.DiagnosticsReport
import io.github.neotheon3.orangebox.core.diagnostics.DtcStatus
import io.github.neotheon3.orangebox.core.diagnostics.TroubleCode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #48 / #49 UI tests and screenshots (written to build/outputs/diagnostics). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h400dp-land-xhdpi")
class DiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()

    private var refreshes = 0
    private var clears = 0

    private fun show(state: DiagnosticsUiState) = compose.setContent {
        DiagnosticsScreen(state, onBack = {}, onRefresh = { refreshes++ }, onClear = { clears++ }, formatTime = testTime)
    }

    private val faulted = DiagnosticsUiState(connected = true, report = acceptanceReport(), history = sampleHistory)

    @Test fun `simulator codes light Ignition, Air-Fuel and Network`() {
        show(faulted)
        compose.onNodeWithTag("dtc_lamp_ignition").assertHasClickAction()
        compose.onNodeWithContentDescription("Ignition: 1 trouble code").assertIsDisplayed()
        // P0420 stored + permanent, P0171 pending.
        compose.onNodeWithContentDescription("Air/Fuel: 3 trouble codes").assertHasClickAction()
        compose.onNodeWithContentDescription("Network: 1 trouble code").assertHasClickAction()
        listOf("injection", "speed", "circuits", "transmission", "powertrain", "body", "chassis").forEach {
            compose.onNodeWithTag("dtc_lamp_$it").assertHasNoClickAction()
        }
        compose.onNodeWithContentDescription("Speed: no trouble codes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vehicle systems: faults in network, engine, exhaust").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics_message").assertTextContains("Tap a red system to see its trouble codes.")
        compose.onNodeWithTag("diagnostics_clear").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics_vin").assertTextContains("VIN 1HGCM82633A004352")
        compose.shoot("diagnostics-land-faults")
    }

    @Config(qualifiers = "w411dp-h891dp-port-xhdpi")
    @Test fun `portrait layout`() {
        show(faulted)
        compose.onNodeWithTag("diagnostics_clear").assertIsDisplayed()
        compose.onNodeWithTag("dtc_lamp_network").performScrollTo().assertHasClickAction()
        compose.shoot("diagnostics-port-faults")
    }

    @Config(qualifiers = "w411dp-h891dp-port-xhdpi")
    @Test fun `portrait, all good`() {
        show(DiagnosticsUiState(connected = true, report = DiagnosticsReport(1_761_370_000_000, emptyList(), false, "1HGCM82633A004352")))
        compose.onNodeWithTag("diagnostics_message").assertTextContains("Car status is good.")
        compose.onNodeWithTag("diagnostics_clear").assertDoesNotExist()
        compose.onNodeWithContentDescription("Vehicle systems: no faults").assertIsDisplayed()
        compose.shoot("diagnostics-port-ok")
    }

    @Test fun `group sheet lists codes with descriptions and earlier codes`() {
        show(faulted)
        compose.onNodeWithTag("dtc_lamp_ignition").performClick()
        compose.onNodeWithTag("dtc_group_sheet").assertIsDisplayed()
        compose.onNodeWithText("Random or multiple cylinder misfire detected.").assertIsDisplayed()
        compose.onNodeWithText("Car maintenance and inspection recommended.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Earlier in this group").performScrollTo().assertIsDisplayed()
        compose.shoot("diagnostics-group-ignition")
    }

    @Test fun `air-fuel sheet shows each list's status`() {
        show(faulted)
        compose.onNodeWithTag("dtc_lamp_air_fuel").performClick()
        compose.onNodeWithTag("dtc_code_P0420_stored").assertIsDisplayed()
        compose.onNodeWithTag("dtc_code_P0420_permanent").assertIsDisplayed()
        compose.onNodeWithTag("dtc_code_P0171_pending").assertIsDisplayed()
        compose.shoot("diagnostics-group-air-fuel")
    }

    @Test fun `unknown codes show the generic fallback`() {
        val report = DiagnosticsReport(0, listOf(TroubleCode("P1234", DtcStatus.STORED), TroubleCode("P0399", DtcStatus.STORED)), true, null)
        show(DiagnosticsUiState(connected = true, report = report))
        compose.onNodeWithTag("dtc_lamp_powertrain").performClick()
        compose.onNodeWithText("Manufacturer-specific code. Check the service information for your car.").assertIsDisplayed()
    }

    @Test fun `clear cancel does nothing, confirm clears`() {
        show(faulted)
        compose.onNodeWithTag("diagnostics_clear").performScrollTo().performClick()
        compose.onNodeWithTag("clear_dialog").assertIsDisplayed()
        compose.onNodeWithText("Manual DTC clearance is not recommended.", substring = true).assertIsDisplayed()
        compose.shoot("diagnostics-clear-confirm")
        compose.onNodeWithTag("clear_cancel").performClick()
        compose.onNodeWithTag("clear_dialog").assertDoesNotExist()
        assertEquals(0, clears)
        compose.onNodeWithTag("diagnostics_clear").performScrollTo().performClick()
        compose.onNodeWithTag("clear_confirm").performClick()
        assertEquals(1, clears)
    }

    @Test fun `clear while moving explains instead of asking`() {
        show(faulted.copy(moving = true))
        compose.onNodeWithTag("diagnostics_clear").performScrollTo().performClick()
        compose.onNodeWithTag("clear_moving_dialog").assertIsDisplayed()
        compose.onNodeWithText("Clearing DTC while driving is not supported. Please stop your car and retry.").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        assertEquals(0, clears)
    }

    @Test fun `refresh, messages and history`() {
        show(DiagnosticsUiState(connected = false, history = sampleHistory))
        compose.onNodeWithTag("diagnostics_message").assertTextContains("Connect to your car to read trouble codes.")
        compose.onNodeWithContentDescription("Refresh").performClick()
        assertEquals(1, refreshes)
        compose.onNodeWithTag("diagnostics_history").performClick()
        compose.onNodeWithTag("dtc_history_sheet").assertIsDisplayed()
        compose.onNodeWithTag("dtc_record_2").assertTextContains("P0301 · Stored, cleared", substring = true)
        compose.onNodeWithTag("dtc_record_1").assertTextContains("P0133 · Pending, went away", substring = true)
        compose.shoot("diagnostics-history")
    }

    @Test fun `notices replace the message`() {
        show(faulted.copy(report = DiagnosticsReport(0, emptyList(), false, null), notice = DiagnosticsNotice.CLEARED))
        compose.onNodeWithTag("diagnostics_message").assertTextContains("Trouble codes cleared.")
    }

    @Test fun `MIL on without codes`() {
        show(DiagnosticsUiState(connected = true, report = DiagnosticsReport(0, emptyList(), true, null)))
        compose.onNodeWithTag("diagnostics_message").assertTextContains("The check-engine lamp is on, but the car reports no codes.")
    }
}
