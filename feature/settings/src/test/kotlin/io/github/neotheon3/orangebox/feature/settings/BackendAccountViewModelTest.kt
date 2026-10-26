package io.github.neotheon3.orangebox.feature.settings

import io.github.neotheon3.orangebox.core.datastore.BackendAccountState
import io.github.neotheon3.orangebox.core.datastore.SignInError
import io.github.neotheon3.orangebox.core.datastore.SignInResult
import io.github.neotheon3.orangebox.core.testing.FakeBackendAccount
import io.github.neotheon3.orangebox.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackendAccountViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    private val account = FakeBackendAccount()

    private fun kotlinx.coroutines.test.TestScope.vm() = BackendAccountViewModel(account).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test fun `e-mail then code signs in and clears the form`() = runTest(main.testDispatcher) {
        val vm = vm()
        vm.onEmailChanged(" kiran@example.com ")
        vm.onSendCode()
        runCurrent()
        assertEquals(listOf("kiran@example.com"), account.codeRequests)
        assertEquals(SignInStep.CODE, vm.uiState.value.form.step)
        assertEquals(60, vm.uiState.value.form.resendInSeconds)

        vm.onCodeChanged("12a3 456789")
        runCurrent()
        assertEquals("only 6 digits are kept", "123456", vm.uiState.value.form.code)
        vm.onVerify()
        runCurrent()
        assertEquals(listOf("kiran@example.com" to "123456"), account.verifications)
        assertTrue(vm.uiState.value.account.signedIn)
        assertEquals(SignInForm(), vm.uiState.value.form)
    }

    @Test fun `an invalid address is caught before any request`() = runTest(main.testDispatcher) {
        val vm = vm()
        vm.onEmailChanged("kiran@")
        vm.onSendCode()
        runCurrent()
        assertTrue(account.codeRequests.isEmpty())
        assertEquals(SignInError.INVALID_EMAIL, vm.uiState.value.form.error)
        assertEquals(SignInStep.EMAIL, vm.uiState.value.form.step)
    }

    @Test fun `resend waits for the server's resendAfter`() = runTest(main.testDispatcher) {
        account.requestCodeResult = SignInResult.CodeSent(resendAfterSeconds = 3, expiresInSeconds = 600)
        val vm = vm()
        vm.onEmailChanged("k@example.com")
        vm.onSendCode()
        runCurrent()
        vm.onResendCode()
        runCurrent()
        assertEquals("too early", 1, account.codeRequests.size)
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(0, vm.uiState.value.form.resendInSeconds)
        vm.onResendCode()
        runCurrent()
        assertEquals(2, account.codeRequests.size)
    }

    @Test fun `a wrong code keeps the code step, too many attempts goes back to the e-mail`() = runTest(main.testDispatcher) {
        val vm = vm()
        vm.onEmailChanged("k@example.com")
        vm.onSendCode()
        runCurrent()
        account.verifyResult = SignInResult.Failed(SignInError.WRONG_CODE)
        vm.onCodeChanged("111111")
        vm.onVerify()
        runCurrent()
        assertEquals(SignInError.WRONG_CODE, vm.uiState.value.form.error)
        assertEquals(SignInStep.CODE, vm.uiState.value.form.step)
        assertEquals("", vm.uiState.value.form.code)

        account.verifyResult = SignInResult.Failed(SignInError.TOO_MANY_ATTEMPTS)
        vm.onCodeChanged("222222")
        vm.onVerify()
        runCurrent()
        assertEquals(SignInStep.EMAIL, vm.uiState.value.form.step)
        assertEquals("k@example.com", vm.uiState.value.form.email)
    }

    @Test fun `locked carries the server's Retry-After`() = runTest(main.testDispatcher) {
        account.requestCodeResult = SignInResult.Failed(SignInError.LOCKED, retryAfterSeconds = 900)
        val vm = vm()
        vm.onEmailChanged("k@example.com")
        vm.onSendCode()
        runCurrent()
        assertEquals(SignInError.LOCKED, vm.uiState.value.form.error)
        assertEquals(900L, vm.uiState.value.form.retryAfterSeconds)
        assertFalse(vm.uiState.value.form.busy)
    }

    @Test fun `sign out asks first, sync now goes to the account`() = runTest(main.testDispatcher) {
        account.state.value = BackendAccountState(signedIn = true, email = "k@example.com")
        val vm = vm()
        vm.onSyncNow()
        runCurrent()
        assertEquals(1, account.syncRequests)

        vm.onSignOutRequested()
        runCurrent()
        assertTrue(vm.uiState.value.confirmSignOut)
        vm.onSignOutDismissed()
        runCurrent()
        assertEquals(0, account.signOuts)
        vm.onSignOutRequested()
        vm.onSignOutConfirmed()
        runCurrent()
        assertEquals(1, account.signOuts)
        assertFalse(vm.uiState.value.account.signedIn)
        assertNull(vm.uiState.value.account.email)
    }
}
