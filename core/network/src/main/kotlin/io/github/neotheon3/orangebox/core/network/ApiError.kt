package io.github.neotheon3.orangebox.core.network

/**
 * A failed call. [Http] wraps a non-2xx answer with its problem+json body (when it had one);
 * [Network] is an I/O failure before an answer arrived. Both are retryable by the caller's rules
 * ([isRetryable]); everything else (other 4xx) is surfaced, not retried.
 */
sealed class ApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    abstract val isRetryable: Boolean

    class Http(
        val status: Int,
        val problem: ProblemDto?,
        /** `Retry-After` in seconds (429 / 503), if sent. */
        val retryAfterSeconds: Long? = null,
        val requestId: String? = null,
    ) : ApiError("HTTP $status${problem?.code?.let { " ($it)" } ?: ""}") {
        val code: String? get() = problem?.code

        /** Only meaningful for [isSyncWindowExpired]; unknown and missing reasons are [SyncWindowReason.UNKNOWN]. */
        val syncWindowReason: SyncWindowReason get() = SyncWindowReason.parse(problem?.reason)

        val isSyncWindowExpired: Boolean get() = status == 400 && code == CODE_SYNC_WINDOW_EXPIRED

        override val isRetryable: Boolean
            get() = status == 408 || status == 429 || status >= 500 || (status == 409 && code == CODE_IDEMPOTENCY_IN_FLIGHT)
    }

    class Network(cause: Throwable) : ApiError("network: ${cause.message ?: cause::class.java.simpleName}", cause) {
        override val isRetryable: Boolean get() = true
    }

    /** No usable session: never signed in, or the refresh token was rejected. Sign in again. */
    class NotSignedIn : ApiError("not signed in") {
        override val isRetryable: Boolean get() = false
    }

    companion object {
        const val CODE_SYNC_WINDOW_EXPIRED = "sync_window_expired"
        const val CODE_VEHICLE_DELETED = "vehicle_deleted"
        const val CODE_VEHICLE_NOT_FOUND = "vehicle_not_found"
        const val CODE_TRIP_DELETED = "trip_deleted"
        const val CODE_IDEMPOTENCY_IN_FLIGHT = "idempotency_in_flight"
        const val CODE_INVALID_GRANT = "invalid_grant"
    }
}
