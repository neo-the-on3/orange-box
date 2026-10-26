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
    /** Retryable failures in a row (drives the backoff); 0 after a success or a final failure. */
    val consecutiveFailures: Int = 0,
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
    /**
     * Remote, signed in: trips on this phone that aren't this account's (recorded signed out, or in another
     * account). They never sync to it unless merged; Settings offers the choice while there are any.
     */
    val otherTrips: Int = 0,
    /** Just signed in with [otherTrips] on the phone: ask merge / replace before anything syncs. */
    val otherTripsChoicePending: Boolean = false,
)

/** The trips "Replace with account's trips" would delete: [count], of which [neverUploaded] exist nowhere else. */
data class OtherTrips(val count: Int, val neverUploaded: Int)

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

    /** What "Replace" would delete (see [BackendAccountState.otherTrips]). */
    suspend fun otherTrips(): OtherTrips = OtherTrips(0, 0)

    /** "Merge with account": the other trips (and their vehicles) become this account's and upload; then sync. */
    suspend fun mergeOtherTrips() {}

    /** "Replace with account's trips": deletes the other trips from this phone, then syncs the account's. */
    suspend fun replaceOtherTrips() {}

    /** Cancel / dismiss: no change; only this account's own trips sync. The choice stays in Settings. */
    suspend fun keepOtherTripsSeparate() {}

    companion object {
        /** The spec's e-mail codes are 6 digits. */
        const val CODE_LENGTH = 6

        /** Loose check before sending; the server has the final say. */
        fun isPlausibleEmail(email: String): Boolean = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    }
}
