package io.github.neotheon3.orangebox.feature.driving

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import io.github.neotheon3.orangebox.core.driving.GeoFix
import io.github.neotheon3.orangebox.core.driving.crash.CrashMonitor
import io.github.neotheon3.orangebox.core.driving.crash.CrashTrigger
import io.github.neotheon3.orangebox.core.driving.crash.EmergencyIntents
import io.github.neotheon3.orangebox.core.model.AppPreferences
import io.github.neotheon3.orangebox.core.model.CrashPreferences
import io.github.neotheon3.orangebox.core.testing.FakeAppPreferencesRepository
import io.github.neotheon3.orangebox.core.testing.FakeObdConnection
import io.github.neotheon3.orangebox.core.testing.FakeTelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import java.io.File

/** #54: the countdown prompt; cancel and timeout, and the dialer / SMS intents it opens (#55). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class CrashPromptScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = FakeAppPreferencesRepository(AppPreferences(crash = CrashPreferences(countdownSeconds = 10)))
    private val monitor = CrashMonitor(scope, FakeObdConnection(), FakeTelemetrySource(), prefs, CrashMonitor.NO_MOTION, System::currentTimeMillis)
    private val fix = GeoFix(12.97160, 77.59457, 6f, 1_761_370_200_000)
    private val controller = CrashPromptController(monitor, scope, prefs.preferences, { "112" }, { fix })

    @After fun tearDown() = scope.cancel()

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { CrashPromptOverlay(controller) }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun shot(name: String) {
        compose.mainClock.advanceTimeByFrame()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/crash/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun nextStarted(): Intent? = shadowOf(compose.activity).nextStartedActivity

    @Test fun `nothing is drawn without a prompt`() {
        show()
        compose.onNodeWithTag("crash_prompt").assertDoesNotExist()
    }

    @Test fun `impact prompt counts down and cancel dismisses it`() {
        show()
        monitor.injectImpact()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("crash_prompt").assertIsDisplayed()
        compose.onNodeWithTag("crash_title").assertTextEquals("Crash detected")
        compose.onNodeWithTag("crash_countdown").assertTextEquals("10")
        shot("crash_prompt_impact")
        compose.onNodeWithTag("crash_cancel").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertNull(monitor.prompt.value)
        compose.onNodeWithTag("crash_prompt").assertDoesNotExist()
        assertNull(nextStarted())
    }

    @Test fun `timeout opens the dialer with the emergency number and ends the prompt`() {
        show()
        monitor.injectImpact()
        val id = monitor.prompt.value!!.id
        // Drive the countdown to its end (the monitor ticks on the main dispatcher).
        repeat(10) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1)) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val dial = nextStarted()!!
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:112", dial.dataString)
        assertTrue(monitor.prompt.value?.id != id)
    }

    @Test fun `call opens the dialer, never a call`() {
        show()
        monitor.injectImpact()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("crash_call").performClick()
        val dial = nextStarted()!!
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertNull(monitor.prompt.value)
    }

    @Test fun `sms opens the SMS app with the location message`() {
        show()
        monitor.injectImpact()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("crash_sms").performClick()
        val sms = nextStarted()!!
        assertEquals(Intent.ACTION_SENDTO, sms.action)
        assertEquals("smsto", sms.data!!.scheme)
        val body = sms.getStringExtra(EmergencyIntents.EXTRA_SMS_BODY)!!
        assertTrue(body, body.startsWith("Orange Box: I may have been in a car accident."))
        assertTrue(body, body.contains("https://www.openstreetmap.org/?mlat=12.97160&mlon=77.59457"))
    }

    @Test fun `manual prompt from the hazard button has no countdown`() {
        show()
        monitor.showManual()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(CrashTrigger.MANUAL, monitor.prompt.value!!.trigger)
        compose.onNodeWithTag("crash_title").assertTextEquals("Emergency")
        compose.onNodeWithTag("crash_countdown").assertDoesNotExist()
        shot("crash_prompt_manual")
        compose.onNodeWithTag("crash_cancel").performClick()
        assertNull(monitor.prompt.value)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-xhdpi") fun `landscape over the dashboard`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            androidx.compose.foundation.layout.Box {
                DashboardScreen(SampleStates.fuelCut(), onEndDriving = {})
                CrashPromptOverlay(controller)
            }
        }
        monitor.injectImpact()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("crash_cancel").assertIsDisplayed()
        shot("crash_prompt_landscape")
    }
}
