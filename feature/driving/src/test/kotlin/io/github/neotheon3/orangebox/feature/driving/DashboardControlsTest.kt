package io.github.neotheon3.orangebox.feature.driving

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class DashboardControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `hazard prompt dials the number from Settings`() {
        compose.setContent { DashboardScreen(SampleStates.fuelCut(), onEndDriving = {}, leftButton = LeftButton.Hazard("911")) }
        compose.onNodeWithTag("dashboard_emergency").performClick()
        compose.onNodeWithText("Open dialer (911)").assertIsDisplayed().performClick()
        val dial = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:911", dial.data.toString())
    }

    @Test fun `quick launcher replaces the hazard button and launches the chosen app`() {
        var launched: String? = null
        compose.setContent {
            DashboardScreen(
                SampleStates.fuelCut(), onEndDriving = {},
                leftButton = LeftButton.QuickLaunch("com.example.maps", "Maps", android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()),
                onQuickLaunch = { launched = it },
            )
        }
        compose.onNodeWithTag("dashboard_emergency").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open Maps").assertIsDisplayed()
        compose.onNodeWithTag("dashboard_quick_launch").performClick()
        assertEquals("com.example.maps", launched)
        compose.onNodeWithTag("emergency_dialog").assertDoesNotExist()
    }

    @Test fun `show when locked sets the lock screen flags while shown and clears them after`() {
        var enabled by mutableStateOf(true)
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) ShowWhenLocked(enabled) }
        compose.waitForIdle()
        val activity = shadowOf(compose.activity)
        assertTrue(activity.showWhenLocked)
        assertTrue(activity.turnScreenOn)
        shown = false
        compose.waitForIdle()
        assertFalse(activity.showWhenLocked)
        assertFalse(activity.turnScreenOn)
        enabled = false
        shown = true
        compose.waitForIdle()
        assertFalse("off unless Show dashboard automatically is on", activity.showWhenLocked)
    }

    @Test fun `dashboard forces light system bar icons while shown and restores them after`() {
        val controller = androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
        compose.runOnUiThread {
            // What enableEdgeToEdge() picks in light mode: dark icons.
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) DashboardScreen(SampleStates.fuelCut(), onEndDriving = {}) }
        compose.waitForIdle()
        assertFalse("white status-bar icons on the black strip", controller.isAppearanceLightStatusBars)
        assertFalse("white nav-bar icons", controller.isAppearanceLightNavigationBars)
        shown = false
        compose.waitForIdle()
        assertTrue("restored after the dashboard leaves", controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
    }
}
