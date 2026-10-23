package io.github.neotheon3.orangebox.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.telemetry.TelemetryEngine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression for the Home-doesn't-scroll bug: everything must be reachable on a 360×640 dp phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class HomeSmallScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `live data rows below the fold can be scrolled into view`() {
        val engine = TelemetryEngine()
        // Coasting at 60 km/h with a low battery so the fuel-cut row and a warning are shown too.
        engine.onSample(VehicleSample(0, mapOf(ObdPid.SPEED to 60.0, ObdPid.RPM to 2000.0, ObdPid.THROTTLE to 0.0)))
        val snap = engine.onSample(
            VehicleSample(
                1_000,
                mapOf(
                    ObdPid.SPEED to 60.0, ObdPid.RPM to 2000.0, ObdPid.THROTTLE to 0.0, ObdPid.MAF to 3.0,
                    ObdPid.COOLANT_TEMP to 90.0, ObdPid.FUEL_LEVEL to 50.0, ObdPid.CONTROL_MODULE_VOLTAGE to 11.0,
                ),
            ),
        )
        val state = HomeUiState(
            backend = BackendConfig(),
            loading = false,
            obd = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1)),
            live = snap,
        )
        compose.setContent { OrangeBoxTheme { HomeScreen(state, onOpenSettings = {}) } }
        // Home must be a vertical scroll container (it was a plain Column before the fix).
        compose.onNodeWithTag("home_scroll").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        listOf("home_live_economy", "home_live_trip", "home_live_fuel_cut", "home_live_warning_low_battery").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
        // …and at 640 dp the content really is taller than the screen.
        val range = compose.onNodeWithTag("home_scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        org.junit.Assert.assertTrue("content should overflow 640 dp", range.maxValue() > 0f)
    }
}
