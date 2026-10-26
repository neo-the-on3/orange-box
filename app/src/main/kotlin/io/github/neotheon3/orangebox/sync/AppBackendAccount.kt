package io.github.neotheon3.orangebox.sync

import android.os.Build
import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.database.SyncDao
import io.github.neotheon3.orangebox.core.datastore.BackendAccount
import io.github.neotheon3.orangebox.core.datastore.BackendAccountState
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.datastore.SignInError
import io.github.neotheon3.orangebox.core.datastore.SignInResult
import io.github.neotheon3.orangebox.core.model.AppVersion
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.network.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [BackendAccount] for Settings: e-mail + one-time code sign-in (`POST /v1/auth/otp`, then `POST /v1/auth/token`
 * with `grantType=email_otp`), sign-out, "Sync now" and the last sync status. No Google services.
 *
 * Off: nothing to sign in to, every call is a no-op and no client is built. Fake: no account is needed (the sync
 * runs against the in-process server). Remote: a session is required before anything syncs.
 */
@Singleton
class AppBackendAccount @Inject constructor(
    private val config: BackendConfigRepository,
    private val clients: BackendClients,
    private val scheduler: SyncScheduler,
    private val status: SyncStatusStore,
    private val syncDao: SyncDao,
    private val version: AppVersion,
    @ApplicationScope private val scope: CoroutineScope,
) : BackendAccount {

    @OptIn(ExperimentalCoroutinesApi::class)
    override val state: Flow<BackendAccountState> = combine(
        config.config,
        clients.sessionStore.sessions,
        status.status,
        config.config.map { it.mode }.distinctUntilChanged().flatMapLatest { mode ->
            if (mode == BackendMode.OFF) flowOf(false) else scheduler.syncing // Off never starts WorkManager
        },
    ) { cfg, session, last, syncing ->
        BackendAccountState(
            signedIn = cfg.mode == BackendMode.REMOTE && session != null,
            email = session?.email.takeIf { cfg.mode == BackendMode.REMOTE },
            lastSync = last.takeIf { cfg.mode != BackendMode.OFF },
            syncing = syncing,
        )
    }.distinctUntilChanged()

    /** Signs out locally when the Remote server changes or Remote is left: the session belongs to that server. */
    fun install() {
        scope.launch {
            config.config.distinctUntilChanged()
                .runningFold(null as BackendConfig? to null as BackendConfig?) { (_, prev), next -> prev to next }
                .collect { (before, after) ->
                    if (before != null && after != null && before.mode == BackendMode.REMOTE &&
                        (after.mode != BackendMode.REMOTE || after.baseUrl != before.baseUrl) && clients.sessionStore.read() != null
                    ) {
                        OrangeLog.i(OrangeLog.Areas.BACKEND, "signed_out_server_changed")
                        forget()
                    }
                }
        }
    }

    private suspend fun remoteApi() = config.config.first().takeIf { it.mode == BackendMode.REMOTE }?.let(clients::forConfig)

    override suspend fun requestCode(email: String): SignInResult {
        if (!BackendAccount.isPlausibleEmail(email)) return SignInResult.Failed(SignInError.INVALID_EMAIL)
        val api = remoteApi() ?: return SignInResult.Failed(SignInError.NOT_CONFIGURED)
        return try {
            val r = api.requestOtp(email.trim())
            SignInResult.CodeSent(resendAfterSeconds = r.resendAfter ?: DEFAULT_RESEND_SECONDS, expiresInSeconds = r.expiresIn)
        } catch (e: ApiError) {
            failure(e, invalidInput = SignInError.INVALID_EMAIL)
        }
    }

    override suspend fun verifyCode(email: String, code: String): SignInResult {
        val digits = code.filter(Char::isDigit)
        if (digits.length != BackendAccount.CODE_LENGTH) return SignInResult.Failed(SignInError.WRONG_CODE)
        val api = remoteApi() ?: return SignInResult.Failed(SignInError.NOT_CONFIGURED)
        return try {
            api.signIn(email.trim(), digits, DeviceInfo(appVersion = version.versionName, osVersion = "Android ${Build.VERSION.RELEASE}"))
            OrangeLog.i(OrangeLog.Areas.BACKEND, "signed_in")
            scheduler.syncSoon()
            SignInResult.SignedIn
        } catch (e: ApiError) {
            failure(e, invalidInput = SignInError.WRONG_CODE)
        }
    }

    override suspend fun signOut() {
        // logout() clears the session even when the call fails (offline, dead session).
        remoteApi()?.logout()
        forget()
        OrangeLog.i(OrangeLog.Areas.BACKEND, "signed_out")
    }

    /**
     * Drops the session and everything known about that account's server state, so the next account starts clean.
     * TODO(M9 question 13 for Go Developer): confirm that a device switching accounts should re-upload its own trips
     *  to the new account and drop the ones pulled from the old one (the interim choice here).
     */
    private suspend fun forget() {
        clients.sessionStore.clear()
        if (androidx.work.WorkManager.isInitialized()) scheduler.cancelAll()
        syncDao.forgetServer()
    }

    override suspend fun syncNow() {
        // Remote needs a session; the runner checks again and does nothing without one.
        if (config.config.first().mode != BackendMode.OFF) scheduler.syncSoon()
    }

    private fun failure(e: ApiError, invalidInput: SignInError): SignInResult.Failed {
        val http = e as? ApiError.Http
        val reason = when {
            e is ApiError.Network -> SignInError.NETWORK
            http == null -> SignInError.SERVER
            http.code == ApiError.CODE_OTP_LOCKED -> SignInError.LOCKED
            http.code == ApiError.CODE_OTP_ATTEMPTS_EXCEEDED -> SignInError.TOO_MANY_ATTEMPTS
            http.code == ApiError.CODE_TEST_ADDRESS_REJECTED -> SignInError.TEST_ADDRESS
            http.code == ApiError.CODE_INVALID_GRANT -> SignInError.WRONG_CODE
            http.status == 429 -> SignInError.RATE_LIMITED
            http.status == 400 || http.status == 422 -> invalidInput
            else -> SignInError.SERVER
        }
        OrangeLog.w(OrangeLog.Areas.BACKEND, "sign_in_failed", "reason" to reason.name, "status" to (http?.status ?: 0))
        return SignInResult.Failed(reason, http?.retryAfterSeconds)
    }

    companion object {
        /** `OtpResponse.resendAfter` is optional; wait this long before offering "Resend" when it's missing. */
        const val DEFAULT_RESEND_SECONDS = 60
    }
}
