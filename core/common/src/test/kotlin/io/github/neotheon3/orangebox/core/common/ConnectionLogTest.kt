package io.github.neotheon3.orangebox.core.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.StringWriter

class ConnectionLogTest {
    @Before fun on() { ConnectionLog.clear(); ConnectionLog.enabled = true; ConnectionLog.clock = { 0L } }
    @After fun off() { ConnectionLog.enabled = false; ConnectionLog.maxChars = ConnectionLog.DEFAULT_MAX_CHARS; ConnectionLog.clear(); ConnectionLog.clock = System::currentTimeMillis }

    @Test fun `disabled records nothing`() {
        ConnectionLog.enabled = false
        ConnectionLog.record("TX", "010C")
        assertTrue(ConnectionLog.snapshot().isEmpty())
    }

    @Test fun `lines carry a timestamp and kind`() {
        ConnectionLog.record("TX", "010C")
        assertTrue(ConnectionLog.snapshot().single().matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}[+-]\d{4} TX 010C""")))
        assertEquals("41 0C\\r>", ConnectionLog.visible("41 0C\r>"))
    }

    @Test fun `the ring buffer keeps the newest lines under the cap and says how many it dropped`() {
        ConnectionLog.maxChars = 2_000
        repeat(1_000) { ConnectionLog.record("TX", "line-$it") }
        val lines = ConnectionLog.snapshot()
        assertTrue(lines.sumOf { it.length + 1 } <= 2_000)
        assertTrue(lines.last().endsWith("line-999"))
        val out = StringWriter().also { ConnectionLog.writeTo(it) }.toString()
        assertTrue(out.startsWith("# ${1_000 - lines.size} older lines dropped"))
    }
}
