package io.github.neotheon3.orangebox.core.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObdConnectionManagerTest {
    /** backgroundScope work is not awaited by advanceUntilIdle, so step virtual time explicitly. */
    private fun TestScope.settle() { advanceTimeBy(120_000); runCurrent() }

    private class Recorder(val scope: TestScope, private val make: () -> ObdTransport) : ObdTransportProvider {
        val createdAt = mutableListOf<Long>()
        override suspend fun create(): ObdTransport {
            createdAt += scope.currentTime
            return make()
        }
    }

    @Test fun `five retries with 2-4-8-16-30 s backoff then Failed`() = runTest {
        val provider = Recorder(this) { FakeObdTransport(openError = ObdError.Io("refused")) }
        val m = ObdConnectionManager(backgroundScope, provider)
        m.connect()
        settle()
        val state = m.state.value
        assertTrue(state is ObdConnectionState.Failed)
        assertEquals(6, (state as ObdConnectionState.Failed).attempts) // initial + 5 retries
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L), provider.createdAt.zipWithNext { a, b -> b - a })
    }

    @Test fun `terminal errors make exactly one attempt`() = runTest {
        for (err in listOf(ObdError.NotPaired(), ObdError.UnsupportedProtocol(), ObdError.PermissionDenied("BLUETOOTH_CONNECT"))) {
            val provider = Recorder(this) { FakeObdTransport(openError = err) }
            val m = ObdConnectionManager(backgroundScope, provider)
            m.connect()
            settle()
            assertEquals(1, provider.createdAt.size)
            assertEquals(err, (m.state.value as ObdConnectionState.Failed).reason)
        }
    }

    @Test fun `provider NotPaired is terminal too`() = runTest {
        val m = ObdConnectionManager(backgroundScope, { throw ObdError.NotPaired() })
        m.connect()
        settle()
        assertTrue((m.state.value as ObdConnectionState.Failed).reason is ObdError.NotPaired)
        assertEquals(1, m.totalAttempts)
    }

    @Test fun `retry from Failed restarts at the first attempt`() = runTest {
        var fail = true
        val provider = Recorder(this) { FakeObdTransport(openError = if (fail) ObdError.Io("x") else null) }
        val m = ObdConnectionManager(backgroundScope, provider)
        m.connect()
        settle()
        assertTrue(m.state.value is ObdConnectionState.Failed)
        fail = false
        val before = provider.createdAt.size
        m.retry()
        runCurrent()
        assertEquals(before + 1, provider.createdAt.size) // immediately, no backoff
        assertTrue(m.state.value is ObdConnectionState.Connected)
    }

    @Test fun `countdown is exposed while waiting`() = runTest {
        val m = ObdConnectionManager(backgroundScope, { FakeObdTransport(openError = ObdError.Io("x")) })
        m.connect()
        runCurrent()
        var s = m.state.value as ObdConnectionState.WaitingToRetry
        assertEquals(1, s.nextRetry); assertEquals(5, s.maxRetries); assertEquals(2, s.secondsRemaining)
        advanceTimeBy(1_001)
        s = m.state.value as ObdConnectionState.WaitingToRetry
        assertEquals(1, s.secondsRemaining)
        advanceTimeBy(1_000)
        s = m.state.value as ObdConnectionState.WaitingToRetry
        assertEquals(2, s.nextRetry); assertEquals(4, s.secondsRemaining)
    }

    @Test fun `mid-session drop re-enters retry loop`() = runTest {
        val transports = mutableListOf<FakeObdTransport>()
        val m = ObdConnectionManager(backgroundScope, { FakeObdTransport().also { transports += it } }, heartbeatMillis = 5_000)
        m.connect()
        runCurrent()
        assertTrue(m.state.value is ObdConnectionState.Connected)
        transports.last().writeError = ObdError.Io("socket closed")
        advanceTimeBy(5_001)
        val s = m.state.value as ObdConnectionState.WaitingToRetry
        assertEquals(1, s.nextRetry)
        assertEquals(1, transports.first().closeCount)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(m.state.value is ObdConnectionState.Connected)
        assertEquals(2, transports.size)
    }

    @Test fun `link down stays down until link up or Connect`() = runTest {
        val events = MutableSharedFlow<DongleLinkEvent>()
        val transports = mutableListOf<FakeObdTransport>()
        val m = ObdConnectionManager(backgroundScope, { FakeObdTransport().also { transports += it } }, linkEvents = events)
        runCurrent()
        events.emit(DongleLinkEvent.Connected("AA"))
        runCurrent()
        assertTrue(m.state.value is ObdConnectionState.Connected)
        events.emit(DongleLinkEvent.Disconnected("AA"))
        runCurrent()
        assertEquals(ObdConnectionState.LinkLost, m.state.value)
        assertEquals(DongleLinkEvent.Disconnected("AA"), m.lastLinkEvent.value)
        assertEquals(1, transports.single().closeCount)
        settle() // no automatic reconnect, however long we wait
        assertEquals(ObdConnectionState.LinkLost, m.state.value)
        assertEquals(1, transports.size)
        events.emit(DongleLinkEvent.Connected("AA"))
        runCurrent()
        assertTrue(m.state.value is ObdConnectionState.Connected)
        events.emit(DongleLinkEvent.Disconnected("AA")); runCurrent()
        m.connect(); runCurrent() // user taps Connect
        assertTrue(m.state.value is ObdConnectionState.Connected)
        assertEquals(3, transports.size)
    }

    @Test fun `link down during retry wait stops the retries`() = runTest {
        val events = MutableSharedFlow<DongleLinkEvent>()
        val rec = Recorder(this) { FakeObdTransport(openError = ObdError.Io("refused")) }
        val m = ObdConnectionManager(backgroundScope, rec, linkEvents = events)
        runCurrent()
        m.connect(); runCurrent()
        assertTrue(m.state.value is ObdConnectionState.WaitingToRetry)
        events.emit(DongleLinkEvent.Disconnected("AA")); runCurrent()
        assertEquals(ObdConnectionState.LinkLost, m.state.value)
        settle()
        assertEquals(1, rec.createdAt.size)
    }

    @Test fun `link down while Idle is ignored`() = runTest {
        val events = MutableSharedFlow<DongleLinkEvent>()
        val m = ObdConnectionManager(backgroundScope, { FakeObdTransport() }, linkEvents = events)
        runCurrent()
        events.emit(DongleLinkEvent.Disconnected("AA")); runCurrent()
        assertEquals(ObdConnectionState.Idle, m.state.value)
    }

    @Test fun `state changes are logged with the OrangeBox tag`() = runTest {
        val lines = mutableListOf<String>()
        io.github.neotheon3.orangebox.core.common.OrangeLog.sink =
            io.github.neotheon3.orangebox.core.common.OrangeLog.Sink { _, tag, msg, _ -> lines += "$tag $msg" }
        try {
            val m = ObdConnectionManager(backgroundScope, { FakeObdTransport(openError = ObdError.Io("refused")) })
            m.connect(); runCurrent(); advanceTimeBy(2_500); runCurrent()
            assertTrue(lines.toString(), lines.any { it.startsWith("OrangeBox/OBD state from=Idle to=Connecting") })
            assertTrue(lines.toString(), lines.any { it.startsWith("OrangeBox/OBD retry_scheduled retry=1/5 delay_s=2") })
            assertTrue(lines.toString(), lines.any { it.contains("attempt_failed") && it.contains("Io(refused)") })
            // the per-second countdown is not logged
            assertEquals(1, lines.count { it.contains("retry=1/5") })
        } finally {
            io.github.neotheon3.orangebox.core.common.OrangeLog.sink = io.github.neotheon3.orangebox.core.common.OrangeLog.Sink { _, _, _, _ -> }
        }
    }

    @Test fun `disconnect returns to Idle and closes transport`() = runTest {
        val t = FakeObdTransport()
        val m = ObdConnectionManager(backgroundScope, { t })
        m.connect(); runCurrent()
        m.disconnect(); runCurrent()
        assertEquals(ObdConnectionState.Idle, m.state.value)
        assertEquals(1, t.closeCount)
    }
}
