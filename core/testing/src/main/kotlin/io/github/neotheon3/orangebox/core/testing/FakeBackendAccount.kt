package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.datastore.BackendAccount
import io.github.neotheon3.orangebox.core.datastore.BackendAccountState
import io.github.neotheon3.orangebox.core.datastore.OtherTrips
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

    /** Trips on the "phone" that aren't the account's; a sign-in with some leaves the choice pending. */
    var phoneOtherTrips = OtherTrips(0, 0)
    val choices = mutableListOf<String>()

    override suspend fun requestCode(email: String): SignInResult {
        codeRequests += email
        return requestCodeResult
    }

    override suspend fun verifyCode(email: String, code: String): SignInResult {
        verifications += email to code
        if (verifyResult == SignInResult.SignedIn) {
            state.value = state.value.copy(
                signedIn = true, email = email.trim().lowercase(),
                otherTrips = phoneOtherTrips.count, otherTripsChoicePending = phoneOtherTrips.count > 0,
            )
        }
        return verifyResult
    }

    override suspend fun signOut() {
        signOuts++
        state.value = state.value.copy(signedIn = false, email = null, lastSync = null)
    }

    override suspend fun syncNow() {
        syncRequests++
    }

    override suspend fun otherTrips() = phoneOtherTrips

    override suspend fun mergeOtherTrips() = answer("merge")

    override suspend fun replaceOtherTrips() = answer("replace")

    override suspend fun keepOtherTripsSeparate() {
        choices += "keep"
        state.value = state.value.copy(otherTripsChoicePending = false)
    }

    private fun answer(choice: String) {
        choices += choice
        phoneOtherTrips = OtherTrips(0, 0)
        state.value = state.value.copy(otherTrips = 0, otherTripsChoicePending = false)
    }
}
