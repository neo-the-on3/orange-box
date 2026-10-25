package io.github.neotheon3.orangebox.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performScrollTo
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private var connects = 0
    private var opens = 0

    private fun render(dark: Boolean, state: HomeUiState, onSettings: () -> Unit = {}, extra: List<HomeMenuEntry> = emptyList()) =
        compose.setContent {
            OrangeBoxTheme(darkTheme = dark) {
                HomeScreen(state, onSettings, onOpenDashboard = { opens++ }, extraMenuEntries = extra, onObdConnect = { connects++ })
            }
        }

    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1))

    @Test fun `renders in light theme, backend pill hidden while Off (#192)`() {
        render(false, HomeUiState(BackendConfig(BackendMode.OFF), loading = false))
        compose.onNodeWithTag("home_backend_mode").assertDoesNotExist()
        compose.onNodeWithTag("home_drive").assertIsDisplayed()
        compose.onNodeWithTag("home_obd_card").assertIsDisplayed()
        compose.onNodeWithTag("home_more").assertDoesNotExist()
    }

    @Test fun `backend pill shows when Fake`() {
        render(false, HomeUiState(BackendConfig(BackendMode.FAKE), loading = false))
        compose.onNodeWithText("Backend: Fake").assertIsDisplayed()
    }

    @Test fun `drive button is the first thing on Home and connects then opens the dashboard when idle (#192)`() {
        render(false, HomeUiState(loading = false))
        val drive = compose.onNodeWithTag("home_drive").assertIsDisplayed()
        drive.assertTextContains("Start driving", substring = true)
        assertTrue(
            "above the OBD card",
            drive.getUnclippedBoundsInRoot().bottom <= compose.onNodeWithTag("home_obd_card").getUnclippedBoundsInRoot().top,
        )
        drive.performClick()
        assertEquals(1, connects)
        assertEquals(1, opens)
    }

    @Test fun `drive button after a failure connects again`() {
        render(false, HomeUiState(loading = false, obd = ObdConnectionState.Failed(ObdError.Timeout(), attempts = 5)))
        compose.onNodeWithTag("home_drive").assertTextContains("Start driving", substring = true).performClick()
        assertEquals(1, connects)
        assertEquals(1, opens)
    }

    @Test fun `drive button only opens the dashboard while connecting or connected (#192)`() {
        var state by mutableStateOf(HomeUiState(loading = false, obd = ObdConnectionState.Connecting(retry = 0, maxRetries = 5)))
        compose.setContent { OrangeBoxTheme { HomeScreen(state, {}, onOpenDashboard = { opens++ }, onObdConnect = { connects++ }) } }
        compose.onNodeWithTag("home_drive").assertTextContains("Open dashboard", substring = true).performClick()
        compose.onNodeWithTag("home_drive").assertTextContains("Connecting", substring = true)
        state = state.copy(obd = connected)
        compose.onNodeWithTag("home_drive").performClick()
        compose.onNodeWithTag("home_drive").assertTextContains("Live data", substring = true)
        assertEquals(0, connects)
        assertEquals(2, opens)
    }

    @Test fun `the whole live data card opens the dashboard (#192)`() {
        render(false, HomeUiState(loading = false, obd = connected))
        compose.onNodeWithTag("home_live_card").performScrollTo().performClick()
        assertEquals(1, opens)
    }

    @Test fun `drive action per state`() {
        assertEquals(DriveAction.CONNECT_AND_DRIVE, DriveAction.of(ObdConnectionState.Idle))
        assertEquals(DriveAction.CONNECT_AND_DRIVE, DriveAction.of(ObdConnectionState.LinkLost))
        assertEquals(DriveAction.OPEN_DASHBOARD, DriveAction.of(ObdConnectionState.Initializing(0, 5)))
        assertEquals(DriveAction.OPEN_DASHBOARD, DriveAction.of(ObdConnectionState.WaitingToRetry(1, 5, 3, ObdError.Timeout())))
        assertEquals(DriveAction.OPEN_DASHBOARD, DriveAction.of(connected))
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
