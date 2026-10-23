package io.github.neotheon3.orangebox.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.telemetry.TelemetryEngine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveDataCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `waiting state`() {
        compose.setContent { OrangeBoxTheme { LiveDataCard(null) } }
        compose.onNodeWithTag("home_live_waiting").assertIsDisplayed()
    }

    @Test fun `shows decoded values and warnings`() {
        val snap = TelemetryEngine().onSample(
            VehicleSample(
                0,
                mapOf(
                    ObdPid.SPEED to 50.0, ObdPid.RPM to 2000.0, ObdPid.COOLANT_TEMP to 115.0, ObdPid.FUEL_LEVEL to 60.0,
                    ObdPid.MAF to 10.0, ObdPid.THROTTLE to 20.0,
                ),
            ),
        )
        compose.setContent { OrangeBoxTheme(darkTheme = true) { LiveDataCard(snap) } }
        compose.onNodeWithTag("home_live_speed").assertTextContains("Speed: 50 km/h")
        compose.onNodeWithTag("home_live_rpm").assertTextContains("RPM: 2000")
        compose.onNodeWithTag("home_live_coolant").assertTextContains("Coolant: 115 °C")
        compose.onNodeWithTag("home_live_fuel_level").assertTextContains("Fuel level: 60 %")
        compose.onNodeWithTag("home_live_fuel_flow").assertTextContains("Fuel flow: 3.29 L/h")
        compose.onNodeWithTag("home_live_economy").assertTextContains("Economy: 6.6 L/100 km")
        compose.onNodeWithTag("home_live_warning_coolant_overheat").assertIsDisplayed()
    }
}
