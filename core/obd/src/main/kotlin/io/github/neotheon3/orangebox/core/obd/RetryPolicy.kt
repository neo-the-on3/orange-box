package io.github.neotheon3.orangebox.core.obd

/**
 * Reconnect policy: after the initial attempt fails, retry up to [maxRetries] times waiting
 * [delaysMillis] (2, 4, 8, 16, 30 s) before each retry; then give up with [ObdConnectionState.Failed].
 */
data class RetryPolicy(
    val delaysMillis: List<Long> = listOf(2_000, 4_000, 8_000, 16_000, 30_000),
) {
    val maxRetries: Int get() = delaysMillis.size

    /** Delay before retry number [retry] (1-based). */
    fun delayBefore(retry: Int): Long = delaysMillis[retry - 1]
}
