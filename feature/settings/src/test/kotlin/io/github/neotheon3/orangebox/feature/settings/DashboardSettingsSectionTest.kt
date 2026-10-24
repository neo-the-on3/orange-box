package io.github.neotheon3.orangebox.feature.settings

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.driving.LaunchableApp
import io.github.neotheon3.orangebox.core.model.DashboardButton
import io.github.neotheon3.orangebox.core.model.DashboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1400dp-port-xhdpi")
class DashboardSettingsSectionTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()
    private val actions = DashboardSettingsActions(
        onButtonChanged = { events += "button:$it" },
        onEmergencyNumberChanged = { events += "number:$it" },
        onPickApp = { events += "pick" },
        onAppChosen = { events += "app:$it" },
        onOverspeedChanged = { events += "overspeed:$it" },
        onAutoShowChanged = { events += "autoShow:$it" },
        onOpenFullScreenSettings = { events += "fullScreenSettings" },
    )

    private fun icon() = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2E7D32.toInt()) }
    private val apps get() = listOf(LaunchableApp("com.example.maps", "Maps", icon()), LaunchableApp("com.example.music", "Music", icon()))

    private fun show(state: DashboardSettingsState, apps: List<LaunchableApp>? = null, dark: Boolean = false) {
        compose.setContent {
            OrangeBoxTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(16.dp)) { DashboardSettingsSection(state, apps, actions) }
                }
            }
        }
    }

    private fun render(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("build/outputs/settings/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun dialogRender(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File("build/outputs/settings/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `hazard mode shows the country default number and the overspeed hysteresis`() {
        show(DashboardSettingsState(country = "IN", defaultEmergencyNumber = "112"))
        compose.onNodeWithTag("settings_button_hazard").assertIsSelected()
        compose.onNodeWithText("Default for your country (IN): 112").assertIsDisplayed()
        compose.onNodeWithTag("settings_emergency_default").assertDoesNotExist()
        compose.onNodeWithText("Starts above 120 km/h, ends below 117 km/h").assertIsDisplayed()
        compose.onNodeWithText("visible without unlocking", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("settings_auto_show").assertIsOff()
        compose.onNodeWithTag("settings_full_screen").assertDoesNotExist()
        render("settings-dashboard-hazard")
    }

    @Test fun `editing the number reports it and a custom number offers the default back`() {
        show(DashboardSettingsState(prefs = DashboardPreferences(emergencyNumber = "100"), country = "IN", defaultEmergencyNumber = "112"))
        compose.onNodeWithTag("settings_emergency_number").performTextReplacement("1x08")
        assertEquals("number:108", events.last())
        compose.onNodeWithTag("settings_emergency_number").performTextReplacement("")
        assertEquals("blank is not saved", "number:108", events.last())
        compose.onNodeWithTag("settings_emergency_default").assertTextContains("Use default (112)").performClick()
        assertEquals("number:", events.last())
    }

    @Test fun `quick launcher opens the picker with icons and reports the chosen app`() {
        show(DashboardSettingsState(), apps = apps)
        compose.onNodeWithTag("settings_button_quick_launcher").performClick()
        assertEquals(listOf("button:QUICK_LAUNCHER", "pick"), events)
        compose.onNodeWithTag("settings_app_picker").assertIsDisplayed()
        dialogRender("settings-dashboard-app-picker")
        compose.onNodeWithTag("settings_app_com.example.music").performClick()
        assertEquals("app:com.example.music", events.last())
        compose.onNodeWithTag("settings_app_picker").assertDoesNotExist()
    }

    @Test fun `launcher mode shows the chosen app and a missing app says the button falls back`() {
        show(
            DashboardSettingsState(
                prefs = DashboardPreferences(button = DashboardButton.QUICK_LAUNCHER, quickLaunchPackage = "com.example.maps", overspeedKph = 100),
                launchApp = apps.first(),
            ),
        )
        compose.onNodeWithTag("settings_launcher_app").assertTextContains("Maps", substring = true)
        compose.onNodeWithTag("settings_emergency_number").assertDoesNotExist()
        compose.onNodeWithText("Starts above 100 km/h, ends below 97 km/h").assertIsDisplayed()
        render("settings-dashboard-launcher")
    }

    @Test fun `missing quick launch app is called out`() {
        show(DashboardSettingsState(prefs = DashboardPreferences(button = DashboardButton.QUICK_LAUNCHER, quickLaunchPackage = "com.example.gone")))
        compose.onNodeWithText("App not installed; the button works as Hazard").assertIsDisplayed()
    }

    @Test fun `slider reports the overspeed in 5 km per h steps`() {
        show(DashboardSettingsState())
        compose.onNodeWithTag("settings_overspeed").performSemanticsAction(SemanticsActions.SetProgress) { it(92f) }
        compose.onNodeWithText("Starts above 90 km/h, ends below 87 km/h").assertIsDisplayed()
    }

    @Test fun `auto-show toggles and without full-screen permission links to system settings`() {
        show(DashboardSettingsState(prefs = DashboardPreferences(autoShowDashboard = true), fullScreenAllowed = false), dark = true)
        compose.onNodeWithTag("settings_auto_show").assertIsOn()
        compose.onNodeWithTag("settings_full_screen_allow").performClick()
        assertEquals("fullScreenSettings", events.last())
        render("settings-dashboard-auto-show-dark")
        compose.onNodeWithTag("settings_auto_show").performClick()
        assertEquals("autoShow:false", events.last())
    }

    @Test fun `auto-show on with the permission granted needs no settings row`() {
        show(DashboardSettingsState(prefs = DashboardPreferences(autoShowDashboard = true), fullScreenAllowed = true))
        compose.onNodeWithTag("settings_full_screen").assertDoesNotExist()
    }
}
