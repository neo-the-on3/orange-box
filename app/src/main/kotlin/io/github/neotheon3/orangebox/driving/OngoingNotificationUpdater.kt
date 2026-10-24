package io.github.neotheon3.orangebox.driving

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Re-posts the ongoing driving notification only when what it displays changes, and at most once per
 * [minIntervalMillis]: changes inside the window are coalesced and the latest one is posted when the
 * window ends (so the final state is never lost). [initial] is what the foreground start already showed.
 */
class OngoingNotificationUpdater(
    private val now: () -> Long,
    private val minIntervalMillis: Long = MIN_INTERVAL_MILLIS,
    private val post: (DrivingNotifications.OngoingContent) -> Unit,
) {
    suspend fun run(content: Flow<DrivingNotifications.OngoingContent>, initial: DrivingNotifications.OngoingContent?) {
        var shown = initial
        var lastPostAt = if (initial != null) now() else Long.MIN_VALUE / 2
        content.distinctUntilChanged().collectLatest { next ->
            if (next == shown) return@collectLatest
            val wait = lastPostAt + minIntervalMillis - now()
            if (wait > 0) delay(wait) // cancelled and restarted by a newer value: only the latest is posted
            post(next)
            shown = next
            lastPostAt = now()
        }
    }

    companion object {
        const val MIN_INTERVAL_MILLIS = 1_000L
    }
}
