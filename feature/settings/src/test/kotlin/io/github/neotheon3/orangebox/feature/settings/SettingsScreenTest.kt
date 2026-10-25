package io.github.neotheon3.orangebox.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `url field only shown in remote mode and clicks report the mode`() {
        var selected: BackendMode? = null
        compose.setContent {
            SettingsScreen(SettingsUiState(config = BackendConfig(BackendMode.OFF)), onBack = {}, onModeSelected = { selected = it }, onUrlChanged = {}, onSaveUrl = {})
        }
        compose.onNodeWithTag("settings_mode_off").assertIsSelected()
        compose.onNodeWithTag("settings_base_url").assertDoesNotExist()
        compose.onNodeWithTag("settings_mode_remote").performClick()
        assertEquals(BackendMode.REMOTE, selected)
    }

    @Test fun `remote mode shows url field in dark theme`() {
        compose.setContent {
            OrangeBoxTheme(darkTheme = true) {
                SettingsScreen(
                    SettingsUiState(config = BackendConfig(BackendMode.REMOTE)),
                    onBack = {}, onModeSelected = {}, onUrlChanged = {}, onSaveUrl = {},
                )
            }
        }
        compose.onNodeWithTag("settings_base_url").assertIsDisplayed()
    }

    @Test fun `about shows version and build`() {
        val v = io.github.neotheon3.orangebox.core.model.AppVersion("0.1.0.9-abc1234-ci57-debug", 9, "abc1234", "57", debug = true)
        compose.setContent {
            io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme {
                SettingsScreen(SettingsUiState(version = v), onBack = {}, onModeSelected = {}, onUrlChanged = {}, onSaveUrl = {})
            }
        }
        compose.onNodeWithTag("settings_about_version").performScrollTo().assertTextContains("Version 0.1.0.9-abc1234-ci57-debug (code 9)")
        compose.onNodeWithTag("settings_about_build").assertTextContains("Commit abc1234 · CI run 57")
    }

    @Test fun `setup rows open their screens and the auto-start switch toggles`() {
        val opened = mutableListOf<String>()
        var autoStart: Boolean? = null
        compose.setContent {
            OrangeBoxTheme {
                SettingsScreen(
                    SettingsUiState(vehicleName = "Swift", dongleName = null, autoStart = false),
                    onBack = {}, onModeSelected = {}, onUrlChanged = {}, onSaveUrl = {},
                    onOpenVehicle = { opened += "vehicle" }, onOpenDongle = { opened += "dongle" },
                    onOpenPermissions = { opened += "permissions" }, onAutoStartChanged = { autoStart = it },
                )
            }
        }
        compose.onNodeWithTag("settings_vehicle").performScrollTo().assertTextContains("Swift", substring = true).performClick()
        compose.onNodeWithTag("settings_dongle").performScrollTo().assertTextContains("Not set up", substring = true).performClick()
        compose.onNodeWithTag("settings_permissions").performScrollTo().performClick()
        compose.onNodeWithTag("settings_auto_start").performScrollTo().assertIsOff().performClick()
        assertEquals(listOf("vehicle", "dongle", "permissions"), opened)
        assertEquals(true, autoStart)
    }

    @Test fun `settings screen includes the driving dashboard section`() {
        var autoShow: Boolean? = null
        compose.setContent {
            OrangeBoxTheme {
                SettingsScreen(
                    SettingsUiState(), onBack = {}, onModeSelected = {}, onUrlChanged = {}, onSaveUrl = {},
                    dashboardActions = DashboardSettingsActions(onAutoShowChanged = { autoShow = it }),
                )
            }
        }
        compose.onNodeWithTag("settings_button_hazard").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("settings_auto_show").performScrollTo().assertIsOff().performClick()
        assertEquals(true, autoShow)
    }

    @Test fun `units section reflects and changes the units (#58)`() {
        val changed = mutableListOf<io.github.neotheon3.orangebox.core.model.UnitPreferences>()
        val metric = io.github.neotheon3.orangebox.core.model.UnitPreferences()
        compose.setContent {
            OrangeBoxTheme {
                SettingsScreen(
                    SettingsUiState(units = metric), onBack = {}, onModeSelected = {}, onUrlChanged = {}, onSaveUrl = {},
                    actions = SettingsActions(onUnitsChanged = { changed += it }),
                )
            }
        }
        compose.onNodeWithTag("settings_units_distance_km").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("settings_units_distance_mi").performClick()
        compose.onNodeWithTag("settings_units_economy_l_per_100km").performScrollTo().performClick()
        compose.onNodeWithTag("settings_units_temperature_fahrenheit").performScrollTo().performClick()
        assertEquals(
            listOf(
                metric.copy(distance = io.github.neotheon3.orangebox.core.model.DistanceUnit.MI),
                metric.copy(economy = io.github.neotheon3.orangebox.core.model.EconomyUnit.L_PER_100KM),
                metric.copy(temperature = io.github.neotheon3.orangebox.core.model.TemperatureUnit.FAHRENHEIT),
            ),
            changed,
        )
    }
}
