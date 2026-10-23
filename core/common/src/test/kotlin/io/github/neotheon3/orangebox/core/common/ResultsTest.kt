package io.github.neotheon3.orangebox.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class ResultsTest {
    @Test fun `wraps success and failure`() {
        assertEquals(3, runCatchingCancellable { 1 + 2 }.getOrThrow())
        assertTrue(runCatchingCancellable { error("boom") }.isFailure)
    }

    @Test(expected = CancellationException::class)
    fun `rethrows cancellation`() {
        runCatchingCancellable { throw CancellationException("cancelled") }
    }
}
