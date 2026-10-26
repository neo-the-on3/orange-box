package io.github.neotheon3.orangebox.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.datastore.BackendAccount
import io.github.neotheon3.orangebox.core.datastore.BackendAccountState
import io.github.neotheon3.orangebox.core.datastore.OtherTrips
import io.github.neotheon3.orangebox.core.datastore.SignInError
import io.github.neotheon3.orangebox.core.datastore.SignInResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SignInStep { EMAIL, CODE }

/** The sign-in form (Remote, signed out). Survives recomposition; cleared once signed in. */
data class SignInForm(
    val step: SignInStep = SignInStep.EMAIL,
    val email: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val error: SignInError? = null,
    /** From `Retry-After` on `otp_locked` / `429`. */
    val retryAfterSeconds: Long? = null,
    /** Seconds until "Resend code" is offered again (the server's `resendAfter`). */
    val resendInSeconds: Int = 0,
)

/** The dialogs about the phone's trips that aren't the signed-in account's (Q13). */
sealed interface OtherTripsDialog {
    /** Merge with account / Replace with account's trips / Cancel. */
    data object Choose : OtherTripsDialog

    /** Second step of Replace: what will be deleted from this phone. */
    data class ConfirmReplace(val trips: OtherTrips) : OtherTripsDialog
}

data class AccountUiState(
    val account: BackendAccountState = BackendAccountState(),
    val form: SignInForm = SignInForm(),
    val confirmSignOut: Boolean = false,
    val otherTripsDialog: OtherTripsDialog? = null,
)

/** Settings → Server → Account and sync (M9): e-mail + one-time code sign-in, status, Sync now, sign-out. */
@HiltViewModel
class BackendAccountViewModel @Inject constructor(private val account: BackendAccount) : ViewModel() {
    private val form = MutableStateFlow(SignInForm())
    private val confirmSignOut = MutableStateFlow(false)
    private var countdown: Job? = null

    /** A dialog opened here (from Settings, or the Replace confirmation). */
    private val dialog = MutableStateFlow<OtherTripsDialog?>(null)

    /** Answered in this screen: don't show the sign-in prompt again while the account catches up. */
    private val answered = MutableStateFlow(false)

    val uiState: StateFlow<AccountUiState> = combine(account.state, form, confirmSignOut, dialog, answered) { a, f, c, d, done ->
        val prompt = if (a.signedIn && a.otherTripsChoicePending && !done) OtherTripsDialog.Choose else null
        AccountUiState(a, f, c, d ?: prompt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState())

    init {
        viewModelScope.launch { account.state.collect { if (!it.otherTripsChoicePending) answered.value = false } }
    }

    /** Settings: "Choose…" next to the trips that don't sync. */
    fun onOtherTripsChoose() { dialog.value = OtherTripsDialog.Choose }

    fun onMerge() = answer { account.mergeOtherTrips() }

    /** Shows what Replace would delete; nothing is deleted before [onReplaceConfirmed]. */
    fun onReplace() {
        viewModelScope.launch {
            val trips = account.otherTrips()
            dialog.value = if (trips.count > 0) OtherTripsDialog.ConfirmReplace(trips) else null
        }
    }

    fun onReplaceConfirmed() = answer { account.replaceOtherTrips() }

    /** Back from the confirmation to the choice; nothing changes. */
    fun onReplaceCancelled() { dialog.value = OtherTripsDialog.Choose }

    /** Cancel or dismiss the choice: no change, only this account's trips sync. */
    fun onOtherTripsDismissed() = answer { account.keepOtherTripsSeparate() }

    private fun answer(action: suspend () -> Unit) {
        answered.value = true
        dialog.value = null
        viewModelScope.launch { action() }
    }

    fun onEmailChanged(value: String) = form.update { it.copy(email = value.trim().take(MAX_EMAIL), error = null) }

    fun onCodeChanged(value: String) = form.update { it.copy(code = value.filter(Char::isDigit).take(BackendAccount.CODE_LENGTH), error = null) }

    fun onSendCode() {
        val email = form.value.email
        if (form.value.busy) return
        if (!BackendAccount.isPlausibleEmail(email)) {
            form.update { it.copy(error = SignInError.INVALID_EMAIL) }
            return
        }
        form.update { it.copy(busy = true, error = null, retryAfterSeconds = null) }
        viewModelScope.launch {
            when (val r = account.requestCode(email)) {
                is SignInResult.CodeSent -> {
                    form.update { it.copy(step = SignInStep.CODE, code = "", busy = false) }
                    startCountdown(r.resendAfterSeconds)
                }
                is SignInResult.Failed -> form.update { it.copy(busy = false, error = r.reason, retryAfterSeconds = r.retryAfterSeconds) }
                SignInResult.SignedIn -> form.update { it.copy(busy = false) }
            }
        }
    }

    fun onResendCode() {
        if (form.value.resendInSeconds > 0) return
        onSendCode()
    }

    fun onVerify() {
        val f = form.value
        if (f.busy) return
        if (f.code.length != BackendAccount.CODE_LENGTH) {
            form.update { it.copy(error = SignInError.WRONG_CODE) }
            return
        }
        form.update { it.copy(busy = true, error = null, retryAfterSeconds = null) }
        viewModelScope.launch {
            when (val r = account.verifyCode(f.email, f.code)) {
                SignInResult.SignedIn -> {
                    countdown?.cancel()
                    form.value = SignInForm()
                }
                is SignInResult.Failed -> form.update {
                    // A spent code can't be retried: go back to asking for a new one.
                    val back = r.reason == SignInError.TOO_MANY_ATTEMPTS
                    it.copy(busy = false, error = r.reason, retryAfterSeconds = r.retryAfterSeconds, code = "", step = if (back) SignInStep.EMAIL else it.step)
                }
                is SignInResult.CodeSent -> form.update { it.copy(busy = false) }
            }
        }
    }

    /** "Use another address": back to the e-mail step. */
    fun onChangeEmail() {
        countdown?.cancel()
        form.update { SignInForm(email = it.email) }
    }

    fun onSyncNow() {
        viewModelScope.launch { account.syncNow() }
    }

    fun onSignOutRequested() { confirmSignOut.value = true }

    fun onSignOutDismissed() { confirmSignOut.value = false }

    fun onSignOutConfirmed() {
        confirmSignOut.value = false
        viewModelScope.launch {
            account.signOut()
            form.value = SignInForm()
        }
    }

    private fun startCountdown(seconds: Int) {
        countdown?.cancel()
        form.update { it.copy(resendInSeconds = seconds.coerceAtLeast(0)) }
        countdown = viewModelScope.launch {
            while (form.value.resendInSeconds > 0) {
                delay(1_000)
                form.update { it.copy(resendInSeconds = (it.resendInSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    companion object {
        /** RFC 5321 path limit. */
        const val MAX_EMAIL = 254
    }
}
