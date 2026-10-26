package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.datastore.BackendAccount
import io.github.neotheon3.orangebox.core.datastore.BackendAccountState
import io.github.neotheon3.orangebox.core.datastore.SignInResult
import kotlinx.coroutines.flow.MutableStateFlow

/** Scriptable [BackendAccount] for UI and ViewModel tests. */
class FakeBackendAccount(initial: BackendAccountState = BackendAccountState()) : BackendAccount {
    override val state = MutableStateFlow(initial)

    var requestCodeResult: SignInResult = SignInResult.CodeSent(resendAfterSeconds = 60, expiresInSeconds = 600)
    var verifyResult: SignInResult = SignInResult.SignedIn
    val codeRequests = mutableListOf<String>()
    val verifications = mutableListOf<Pair<String, String>>()
    var signOuts = 0
    var syncRequests = 0

    override suspend fun requestCode(email: String): SignInResult {
        codeRequests += email
        return requestCodeResult
    }

    override suspend fun verifyCode(email: String, code: String): SignInResult {
        verifications += email to code
        if (verifyResult == SignInResult.SignedIn) state.value = state.value.copy(signedIn = true, email = email.trim().lowercase())
        return verifyResult
    }

    override suspend fun signOut() {
        signOuts++
        state.value = state.value.copy(signedIn = false, email = null, lastSync = null)
    }

    override suspend fun syncNow() {
        syncRequests++
    }
}
