package io.github.neotheon3.orangebox.core.obd

import io.github.neotheon3.orangebox.core.common.ConnectionLog
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LoggingObdTransportTest {
    @Before fun on() { ConnectionLog.clear(); ConnectionLog.enabled = true }
    @After fun off() { ConnectionLog.enabled = false; ConnectionLog.clear() }

    private fun kinds() = ConnectionLog.snapshot().map { it.substringAfter(' ') }

    @Test fun `records open, TX, RX with timing and close, passing results through`() = runTest {
        val fake = FakeObdTransport()
        val t = LoggingObdTransport(fake, "bt")
        t.open()
        t.writeLine("ATZ")
        assertEquals("\r\rELM327 v1.5", t.readUntilPrompt(1_000))
        t.close()
        val k = kinds()
        assertEquals("OPEN bt start", k[0])
        assertTrue(k[1].matches(Regex("OPEN bt ok \\d+ms")))
        assertEquals("TX ATZ", k[2])
        assertTrue(k[3], k[3].matches(Regex("""RX \\r\\rELM327 v1\.5 \(\d+ms\)""")))
        assertEquals("CLOSE bt", k[4])
        assertEquals(listOf("ATZ"), fake.written)
        assertEquals(1, fake.closeCount)
    }

    @Test fun `errors are recorded and rethrown unchanged`() = runTest {
        val boom = ObdError.Io("socket closed")
        val t = LoggingObdTransport(FakeObdTransport(openError = boom), "bt")
        try { t.open(); fail() } catch (e: ObdError) { assertSame(boom, e) }
        val timeout = LoggingObdTransport(FakeObdTransport(responder = { null }), "sim")
        timeout.writeLine("010C")
        try { timeout.readUntilPrompt(5); fail() } catch (e: ObdError.Timeout) { }
        val k = kinds()
        assertTrue(k.any { it.startsWith("OPEN bt failed") && it.contains("Io(socket closed)") })
        assertTrue(k.any { it.startsWith("ERR read after") && it.contains("timeout 5ms") && it.contains("Timeout") })
    }

    @Test fun `wrapping is skipped while the log is disabled`() {
        ConnectionLog.enabled = false
        val fake = FakeObdTransport()
        assertSame(fake, fake.loggedIfEnabled("bt"))
        ConnectionLog.enabled = true
        assertTrue(fake.loggedIfEnabled("bt") is LoggingObdTransport)
    }
}
