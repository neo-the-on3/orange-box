package io.github.neotheon3.orangebox.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.neotheon3.orangebox.core.datastore.BackendAccountState
import io.github.neotheon3.orangebox.core.datastore.SyncError
import io.github.neotheon3.orangebox.core.datastore.SyncStatus
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.core.model.BackendMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountSettingsSectionTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = AccountActions(
        onEmailChanged = { calls += "email:$it" },
        onSendCode = { calls += "send" },
        onCodeChanged = { calls += "code:$it" },
        onVerify = { calls += "verify" },
        onResendCode = { calls += "resend" },
        onSyncNow = { calls += "sync" },
        onSignOutRequested = { calls += "signout?" },
        onSignOutConfirmed = { calls += "signout" },
        onOtherTripsChoose = { calls += "choose" },
        onMerge = { calls += "merge" },
        onReplace = { calls += "replace?" },
        onReplaceConfirmed = { calls += "replace" },
        onReplaceCancelled = { calls += "replace-cancel" },
        onOtherTripsDismissed = { calls += "keep" },
    )

    private fun show(state: AccountUiState, mode: BackendMode = BackendMode.REMOTE, ready: Boolean = true) = compose.setContent {
        OrangeBoxTheme { Surface { AccountSettingsSection(state, mode, ready, actions) } }
    }

    @Test fun `off shows nothing`() {
        show(AccountUiState(), BackendMode.OFF)
        compose.onNodeWithTag("settings_account").assertDoesNotExist()
    }

    @Test fun `remote without a saved address asks for one first`() {
        show(AccountUiState(), ready = false)
        compose.onNodeWithTag("account_needs_url").assertIsDisplayed()
        compose.onNodeWithTag("account_email").assertDoesNotExist()
    }

    @Test fun `e-mail step sends the code`() {
        show(AccountUiState(form = SignInForm(email = "k@example.com")))
        compose.onNodeWithTag("account_email").performTextInput("x")
        compose.onNodeWithTag("account_send_code").assertIsEnabled().performClick()
        assertEquals(listOf("email:xk@example.com", "send"), calls)
    }

    @Test fun `code step - sign in needs six digits, resend waits`() {
        show(AccountUiState(form = SignInForm(step = SignInStep.CODE, email = "k@example.com", code = "123", resendInSeconds = 42)))
        compose.onNodeWithTag("account_code_help").assertTextContains("k@example.com", substring = true)
        compose.onNodeWithTag("account_verify").assertIsNotEnabled()
        compose.onNodeWithTag("account_resend").assertIsNotEnabled().assertTextContains("42", substring = true)
    }

    @Test fun `signed in shows who, the last sync, sync now and sign out`() {
        show(AccountUiState(BackendAccountState(signedIn = true, email = "k@example.com", lastSync = SyncStatus(1_761_450_000_000, ok = true, uploadedTrips = 3))))
        compose.onNodeWithText("Signed in as k@example.com").assertIsDisplayed()
        compose.onNodeWithText("3 trips uploaded", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("account_sync_now").performClick()
        compose.onNodeWithTag("account_sign_out").performClick()
        assertEquals(listOf("sync", "signout?"), calls)
    }

    @Test fun `a failed sync shows why and that it retries, and sync now is off while syncing`() {
        show(
            AccountUiState(
                BackendAccountState(signedIn = true, email = "k@example.com", syncing = true,
                    lastSync = SyncStatus(1_761_450_000_000, ok = false, error = SyncError.NETWORK, willRetry = true)),
            ),
        )
        compose.onNodeWithTag("account_sync_error").assertTextContains("No connection", substring = true).assertTextContains("Retrying", substring = true)
        compose.onNodeWithTag("account_syncing").assertIsDisplayed()
        compose.onNodeWithTag("account_sync_now").assertIsNotEnabled()
    }

    @Test fun `fake mode has no sign-in and no sign out`() {
        show(AccountUiState(), BackendMode.FAKE)
        compose.onNodeWithTag("account_email").assertDoesNotExist()
        compose.onNodeWithTag("account_sign_out").assertDoesNotExist()
        compose.onNodeWithTag("account_sync_now").assertIsDisplayed()
    }

    @Test fun `sign out is confirmed in a dialog`() {
        show(AccountUiState(BackendAccountState(signedIn = true), confirmSignOut = true))
        compose.onNodeWithTag("account_sign_out_confirm").performClick()
        assertEquals(listOf("signout"), calls)
    }

    private val signedIn = BackendAccountState(signedIn = true, email = "kiran@example.com", otherTrips = 3)

    @Test fun `trips that don't sync are listed with the choice offered again`() {
        show(AccountUiState(signedIn))
        compose.onNodeWithText("3 trips on this phone", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("account_other_trips_choose").performClick()
        assertEquals(listOf("choose"), calls)
    }

    @Test fun `the choice dialog offers merge, replace and cancel`() {
        show(AccountUiState(signedIn, otherTripsDialog = OtherTripsDialog.Choose))
        compose.onNodeWithText("This phone has 3 trips that aren't in kiran@example.com", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("account_merge").performClick()
        compose.onNodeWithTag("account_replace").performClick()
        compose.onNodeWithTag("account_other_trips_cancel").performClick()
        assertEquals(listOf("merge", "replace?", "keep"), calls)
    }

    @Test fun `the replace confirmation counts what's deleted and what's lost for good`() {
        show(AccountUiState(signedIn, otherTripsDialog = OtherTripsDialog.ConfirmReplace(io.github.neotheon3.orangebox.core.datastore.OtherTrips(3, 2))))
        compose.onNodeWithText("Delete 3 trips from this phone?").assertIsDisplayed()
        compose.onNodeWithTag("account_replace_never_uploaded").assertTextContains("2 of them were never uploaded and will be lost permanently.")
        compose.onNodeWithTag("account_replace_cancel").performClick()
        compose.onNodeWithTag("account_replace_confirm").assertTextContains("Delete 3 trips").performClick()
        assertEquals(listOf("replace-cancel", "replace"), calls)
    }

    @Test fun `no never-uploaded line when every trip is on a server`() {
        show(AccountUiState(signedIn, otherTripsDialog = OtherTripsDialog.ConfirmReplace(io.github.neotheon3.orangebox.core.datastore.OtherTrips(1, 0))))
        compose.onNodeWithText("Delete 1 trip from this phone?").assertIsDisplayed()
        compose.onNodeWithTag("account_replace_never_uploaded").assertDoesNotExist()
    }
}
