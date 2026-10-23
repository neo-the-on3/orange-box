package io.github.neotheon3.orangebox.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
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
}
