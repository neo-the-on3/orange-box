package io.github.neotheon3.orangebox.feature.driving

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.dashboardui.DashboardSettings
import io.github.neotheon3.orangebox.core.dashboardui.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardScreenTest {
    @get:Rule val compose = createComposeRule()

    private var ended = 0

    private fun show(state: DashboardUiState = SampleStates.fuelCut(), fontScale: Float = 1f, reduceMotion: Boolean = true) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                DashboardScreen(state, onEndDriving = { ended++ }, settings = DashboardSettings(reduceMotion = reduceMotion))
            }
        }
    }

    private fun assertClusters() {
        compose.onNodeWithContentDescription("Engine speed 1698 rpm").assertIsDisplayed()
        compose.onNodeWithContentDescription("Speed 59 km/h").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fuel-cut, active").assertIsDisplayed()
        compose.onNodeWithContentDescription("Overspeed, off").assertIsDisplayed()
        compose.onNodeWithContentDescription("OBD, on").assertIsDisplayed()
        compose.onNodeWithContentDescription("Trip economy 8.9 km/L, instant 30.0 km/L").assertIsDisplayed()
        compose.onNodeWithContentDescription("Trip 2.0 km, 0:04:29").assertIsDisplayed()
        listOf("dashboard_emergency", "dashboard_diagnostics", "dashboard_detail_toggle", "dashboard_end_driving").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed().assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        }
    }

    /** Nothing important is pushed off screen. */
    private fun assertInsideRoot(vararg tags: String) {
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        tags.forEach {
            val b = compose.onNodeWithTag(it).getUnclippedBoundsInRoot()
            assertTrue("$it inside the screen: $b vs $root", b.left >= root.left && b.right <= root.right && b.bottom <= root.bottom && b.top >= root.top)
        }
    }

    @Config(qualifiers = "w640dp-h360dp-land") @Test fun `landscape at font scale 1`() {
        show(); assertClusters(); assertInsideRoot("cluster_rpm", "cluster_speed", "card_fuel", "card_trip", "dashboard_bottom_bar")
    }

    @Config(qualifiers = "w640dp-h360dp-land") @Test fun `landscape at font scale 2`() {
        show(fontScale = 2f); assertClusters(); assertInsideRoot("cluster_rpm", "cluster_speed", "dashboard_bottom_bar")
    }

    @Config(qualifiers = "w360dp-h640dp-port") @Test fun `portrait at font scale 1`() {
        show(); assertClusters(); assertInsideRoot("cluster_rpm", "cluster_speed", "card_fuel", "card_trip", "connect_chips", "dashboard_bottom_bar")
    }

    @Config(qualifiers = "w360dp-h640dp-port") @Test fun `portrait at font scale 2`() {
        show(fontScale = 2f); assertClusters(); assertInsideRoot("cluster_rpm", "cluster_speed", "dashboard_bottom_bar")
    }

    @Config(qualifiers = "w640dp-h360dp-land") @Test fun `no data shows placeholders`() {
        show(DashboardUiState())
        compose.onNodeWithContentDescription("Engine speed --- rpm").assertIsDisplayed()
        compose.onNodeWithContentDescription("Speed --- km/h").assertIsDisplayed()
        compose.onNodeWithContentDescription("Trip --.- km, -:--:--").assertIsDisplayed()
        compose.onNodeWithContentDescription("OBD, off").assertIsDisplayed()
    }

    @Config(qualifiers = "w640dp-h360dp-land") @Test fun `Detailed Info toggles the grid and pages wrap`() {
        show(reduceMotion = false)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("dashboard_detail_toggle")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "clusters shown"))
            .performClick()
        compose.mainClock.advanceTimeBy(250)
        // Mid-transition both are composed.
        compose.onNodeWithTag("cluster_rpm").assertExists()
        compose.onNodeWithTag("detail_grid").assertExists()
        compose.mainClock.advanceTimeBy(DETAIL_TRANSITION_MILLIS.toLong())
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("cluster_rpm").assertDoesNotExist()
        compose.onNodeWithTag("dashboard_detail_toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "detail grid shown"))
        compose.onNodeWithContentDescription("Accel Pedal Position, 15 %").assertIsDisplayed()
        compose.onNodeWithContentDescription("Fuel Level, no data").assertIsDisplayed()
        compose.onNodeWithContentDescription("Battery Voltage, 14.0 V").assertIsDisplayed()
        compose.onNodeWithContentDescription("MIL, 0 ea").assertIsDisplayed()
        // Page 1 → 2 → 3 → wraps to 1.
        compose.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("ST Fuel Trim, 1.6 %").assertIsDisplayed()
        compose.onNodeWithContentDescription("O2 Sensor Volt., no data").assertIsDisplayed()
        compose.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Eco-speed Time, 0:01:31").assertIsDisplayed()
        compose.onNodeWithTag("detail_pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Accel Pedal Position, 15 %").assertIsDisplayed()
        // And back.
        compose.onNodeWithTag("dashboard_detail_toggle").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("detail_grid").assertDoesNotExist()
        compose.onNodeWithTag("cluster_rpm").assertIsDisplayed()
    }

    @Config(qualifiers = "w640dp-h360dp-land") @Test fun `reduce motion snaps the transition`() {
        show(reduceMotion = true)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("dashboard_detail_toggle").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("cluster_rpm").assertDoesNotExist()
        compose.onNodeWithTag("detail_grid").assertExists()
    }

    @Config(qualifiers = "w640dp-h360dp-land") @Test fun `hazard opens the emergency prompt, diagnostics the stub, power ends the drive`() {
        show()
        compose.onNodeWithContentDescription("Emergency").performClick()
        compose.onNodeWithTag("emergency_dialog").assertIsDisplayed()
        compose.onNodeWithTag("emergency_dial").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("emergency_dialog").assertDoesNotExist()
        compose.onNodeWithContentDescription("Diagnostics").performClick()
        compose.onNodeWithTag("diagnostics_dialog").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("End driving").performClick()
        assertEquals(1, ended)
    }
}
