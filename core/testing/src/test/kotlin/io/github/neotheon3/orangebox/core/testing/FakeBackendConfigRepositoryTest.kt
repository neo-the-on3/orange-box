package io.github.neotheon3.orangebox.core.testing

import app.cash.turbine.test
import io.github.neotheon3.orangebox.core.model.BackendMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FakeBackendConfigRepositoryTest {
    @Test fun `emits mode changes`() = runTest {
        val repo = FakeBackendConfigRepository()
        repo.config.test {
            assertEquals(BackendMode.OFF, awaitItem().mode)
            repo.setMode(BackendMode.REMOTE)
            assertEquals(BackendMode.REMOTE, awaitItem().mode)
        }
    }

    /** Sample of Turbine + virtual time: a 30 s delay completes instantly. */
    @Test fun `virtual time skips delays`() = runTest {
        val ticks = flow { repeat(3) { delay(30_000); emit(it) } }
        ticks.test {
            assertEquals(0, awaitItem())
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            awaitComplete()
        }
        assertEquals(90_000, currentTime)
    }
}
