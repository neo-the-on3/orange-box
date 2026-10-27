package io.github.neotheon3.orangebox.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.github.neotheon3.orangebox.core.dashboardui.DashboardStateMapper
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.telemetry.TelemetryEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniDashboardCardTest {
    @get:Rule val compose = createComposeRule()

    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1))
    private val snap = TelemetryEngine().onSample(
        VehicleSample(
            0,
            mapOf(
                ObdPid.SPEED to 50.0, ObdPid.RPM to 2000.0, ObdPid.COOLANT_TEMP to 115.0, ObdPid.FUEL_LEVEL to 60.0,
                ObdPid.MAF to 10.0, ObdPid.THROTTLE to 20.0, ObdPid.ENGINE_LOAD to 30.0,
            ),
        ),
    )

    private fun show(fontScale: Float = 1f, onOpen: () -> Unit = {}) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            OrangeBoxTheme { MiniDashboardCard(DashboardStateMapper.map(connected, snap), snap, onOpen) }
        }
    }

    private fun assertValues() {
        compose.onNodeWithContentDescription("Speed 50 kilometres per hour").assertIsDisplayed()
        compose.onNodeWithContentDescription("Engine speed 2000 rpm").assertIsDisplayed()
        compose.onNodeWithContentDescription("Coolant, warning").assertIsDisplayed() // 115 °C ≥ 100
        compose.onNodeWithContentDescription("Eco, green").assertIsDisplayed()
        compose.onNodeWithContentDescription("OBD, on").assertIsDisplayed()
        compose.onNodeWithTag("home_live_coolant").assertTextContains("Coolant: 115 °C")
        compose.onNodeWithTag("home_live_fuel_level").assertTextContains("Fuel level: 60 %")
        compose.onNodeWithTag("home_live_fuel_flow").assertTextContains("Fuel flow: 3.29 L/h")
        compose.onNodeWithTag("home_live_warning_coolant_overheat").assertIsDisplayed()
    }

    @Config(qualifiers = "w411dp-h891dp") @Test fun `side by side on a normal phone`() {
        show(); assertValues()
        val rpm = compose.onNodeWithTag("cluster_rpm").getUnclippedBoundsInRoot()
        val speed = compose.onNodeWithTag("cluster_speed").getUnclippedBoundsInRoot()
        assertEquals("same row", rpm.top, speed.top)
    }

    @Config(qualifiers = "w320dp-h640dp") @Test fun `stacks below 360 dp at 200 percent font`() {
        show(fontScale = 2f); assertValues()
        val rpm = compose.onNodeWithTag("cluster_rpm").getUnclippedBoundsInRoot()
        val speed = compose.onNodeWithTag("cluster_speed").getUnclippedBoundsInRoot()
        assertTrue("stacked", speed.top >= rpm.bottom)
    }

    @Test fun `tap opens the dashboard`() {
        var opened = 0
        show(onOpen = { opened++ })
        compose.onNodeWithTag("home_open_dashboard").performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("home_live_card").performClick() // the whole card, #192
        assertEquals(2, opened)
    }

    @Test fun `waiting for the first sample`() {
        compose.setContent { OrangeBoxTheme { MiniDashboardCard(DashboardStateMapper.map(connected, null), null, {}) } }
        compose.onNodeWithTag("home_live_waiting").assertIsDisplayed()
        compose.onNodeWithContentDescription("Speed, no data").assertIsDisplayed()
    }
}
