package io.github.neotheon3.orangebox.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching] but never swallows coroutine cancellation, so structured
 * concurrency keeps working inside retry loops.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
