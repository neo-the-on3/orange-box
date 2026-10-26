package io.github.neotheon3.orangebox.core.datastore

import kotlinx.coroutines.flow.Flow

/** Outcome of the last sync run, shown in Settings → Server. */
data class SyncStatus(
    val atMillis: Long,
    val ok: Boolean,
    /** Short, user-facing reason when [ok] is false (no tokens, no addresses). */
    val error: SyncError? = null,
    val uploadedTrips: Int = 0,
    val pulledTrips: Int = 0,
    val telemetryBatches: Int = 0,
    /** A retry is scheduled (the error should clear by itself). */
    val willRetry: Boolean = false,
)

/** Why a sync or a sign-in step failed, as Settings words it. */
enum class SyncError { NETWORK, SERVER_BUSY, SIGNED_OUT, REJECTED, RECOVERY, UNKNOWN }

data class BackendAccountState(
    val signedIn: Boolean = false,
    /** The address signed in with (Remote), null when signed out or unknown. */
    val email: String? = null,
    val lastSync: SyncStatus? = null,
    /** A "Sync now" run is queued or running. */
    val syncing: Boolean = false,
)

/** Result of the e-mail + one-time-code steps. */
sealed interface SignInResult {
    /** A code was sent (or the address is rate-limited silently, per the spec): the resend button waits [resendAfterSeconds]. */
    data class CodeSent(val resendAfterSeconds: Int, val expiresInSeconds: Int) : SignInResult

    data object SignedIn : SignInResult

    data class Failed(val reason: SignInError, val retryAfterSeconds: Long? = null) : SignInResult
}

enum class SignInError {
    /** Not a usable e-mail address (checked before sending, or the server's 400/422). */
    INVALID_EMAIL,

    /** Wrong or expired code (`invalid_grant`). */
    WRONG_CODE,

    /** Five wrong codes (`otp_attempts_exceeded`): request a new one. */
    TOO_MANY_ATTEMPTS,

    /** `otp_locked`: the address is locked out for a while. */
    LOCKED,

    /** `429`: too many requests from this network. */
    RATE_LIMITED,

    /** `test_address_rejected`: the reserved test domain on a production server. */
    TEST_ADDRESS,
    NETWORK,
    SERVER,

    /** Remote mode without a valid server address. */
    NOT_CONFIGURED,
}

/**
 * The member account and the sync, for Settings. Implemented in the app (it owns the backend clients and
 * WorkManager); Off has nothing to sign in to and every call is a no-op.
 */
interface BackendAccount {
    val state: Flow<BackendAccountState>

    suspend fun requestCode(email: String): SignInResult

    suspend fun verifyCode(email: String, code: String): SignInResult

    /** `POST /v1/auth/logout` when reachable; always clears the local session. */
    suspend fun signOut()

    /** Queues a sync run now (Fake, or Remote when signed in). */
    suspend fun syncNow()

    companion object {
        /** The spec's e-mail codes are 6 digits. */
        const val CODE_LENGTH = 6

        /** Loose check before sending; the server has the final say. */
        fun isPlausibleEmail(email: String): Boolean = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    }
}
