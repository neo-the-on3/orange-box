package io.github.neotheon3.orangebox.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.testing.FakeBackendConfigRepository
import io.github.neotheon3.orangebox.core.testing.FakeObdConnection
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdStatusCardTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val main = MainDispatcherRule()

    private val obd = FakeObdConnection()
    private val vm by lazy { HomeViewModel(FakeBackendConfigRepository(), obd, io.github.neotheon3.orangebox.core.testing.FakeTelemetrySource()) }

    private fun render() = compose.setContent { OrangeBoxTheme { HomeRoute(onOpenSettings = {}, onOpenDashboard = {}, viewModel = vm) } }

    @Test fun `idle shows connect which calls connect`() {
        render()
        compose.onNodeWithTag("home_obd_status").assertTextContains("Not connected")
        compose.onNodeWithTag("home_obd_connect").performClick()
        assertEquals(1, obd.connectCalls)
    }

    @Test fun `connecting shows retry counter and countdown`() {
        render()
        obd.state.value = ObdConnectionState.Connecting(retry = 2, maxRetries = 5)
        compose.onNodeWithTag("home_obd_status").assertTextContains("retry 2/5", substring = true)
        obd.state.value = ObdConnectionState.WaitingToRetry(3, 5, 8, ObdError.Io("refused"))
        compose.onNodeWithTag("home_obd_status").assertTextContains("Retry 3/5 in 8 s")
        compose.onNodeWithTag("home_obd_error").assertTextContains("refused", substring = true)
        compose.onNodeWithTag("home_obd_cancel").performClick()
        assertEquals(1, obd.disconnectCalls)
    }

    @Test fun `connected shows protocol and voltage`() {
        render()
        obd.state.value = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1))
        compose.onNodeWithTag("home_obd_status").assertTextContains("Connected")
        compose.onNodeWithTag("home_obd_protocol").assertTextContains("CAN 11/500", substring = true)
        compose.onNodeWithTag("home_obd_voltage").assertTextContains("14.1 V", substring = true)
    }

    @Test fun `failed shows reason and retry calls retry`() {
        render()
        obd.state.value = ObdConnectionState.Failed(ObdError.UnsupportedProtocol(), attempts = 1)
        compose.onNodeWithTag("home_obd_error").assertTextContains("not supported", substring = true)
        compose.onNodeWithTag("home_obd_retry").assertIsDisplayed().performClick()
        assertEquals(1, obd.retryCalls)
    }

    @Test fun `backend pill still renders`() {
        render()
        compose.onNodeWithText("Backend: Off").assertIsDisplayed()
    }
}
