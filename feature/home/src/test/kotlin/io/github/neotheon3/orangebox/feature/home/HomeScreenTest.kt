package io.github.neotheon3.orangebox.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun render(dark: Boolean, state: HomeUiState, onSettings: () -> Unit = {}, extra: List<HomeMenuEntry> = emptyList()) =
        compose.setContent { OrangeBoxTheme(darkTheme = dark) { HomeScreen(state, onSettings, extraMenuEntries = extra) } }

    @Test fun `renders in light theme`() {
        render(false, HomeUiState(BackendConfig(BackendMode.OFF), loading = false))
        compose.onNodeWithText("Backend: Off").assertIsDisplayed()
        compose.onNodeWithTag("home_obd_card").assertIsDisplayed()
        compose.onNodeWithTag("home_more").assertDoesNotExist()
    }

    @Test fun `renders in dark theme with remote warning`() {
        render(true, HomeUiState(BackendConfig(BackendMode.REMOTE), loading = false))
        compose.onNodeWithText("Backend: Remote").assertIsDisplayed()
        compose.onNodeWithTag("home_remote_warning").assertIsDisplayed()
    }

    @Test fun `settings and overflow entries are clickable`() {
        var settings = false
        var extra = false
        render(false, HomeUiState(loading = false), { settings = true }, listOf(HomeMenuEntry("Extra", "home_extra") { extra = true }))
        compose.onNodeWithTag("home_settings").performClick()
        compose.onNodeWithTag("home_more").performClick()
        compose.onNodeWithTag("home_extra").performClick()
        assertTrue(settings && extra)
    }
}
