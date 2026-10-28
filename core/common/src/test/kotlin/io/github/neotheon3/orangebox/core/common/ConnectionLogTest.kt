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
        // Stored compactly ("0 TX line-n"), so the cap counts that, not the formatted line.
        assertTrue(lines.sumOf { it.substringAfter(' ').length + 3 } <= 2_000)
        assertTrue(lines.last().endsWith("line-999"))
        val out = StringWriter().also { ConnectionLog.writeTo(it) }.toString()
        assertTrue(out.startsWith("# ${1_000 - lines.size} older lines dropped"))
    }

    @Test fun `session headers survive the ring buffer and keep the last three`() {
        ConnectionLog.maxChars = 500
        ConnectionLog.pin("vehicle", "fuel=petrol", sticky = true)
        repeat(4) { n ->
            ConnectionLog.newSession("bt")
            ConnectionLog.pin("adapter", "ELM327 v1.5")
            ConnectionLog.pin("protocol", "ISO_15765_4_CAN_11_500 #$n")
            repeat(200) { ConnectionLog.record("TR", "010D1 > 410D00 (50ms)") }
        }
        val out = StringWriter().also { ConnectionLog.writeTo(it) }.toString().lines()
        val header = out.takeWhile { it.startsWith("#") }
        assertEquals(listOf(2, 3, 4), header.filter { it.startsWith("# session") }.map { it.substringAfter("# session ").substringBefore(' ').toInt() })
        assertTrue(header.contains("#   protocol: ISO_15765_4_CAN_11_500 #3"))
        assertEquals(3, header.count { it == "#   vehicle: fuel=petrol" })
        assertTrue(header.last().contains("older lines dropped"))
    }

    @Test fun `reply trims the prompt and line breaks`() {
        assertEquals("410D00", ConnectionLog.reply("410D00\r\r>"))
        assertEquals("SEARCHING...\\r41 00 BE", ConnectionLog.reply("\rSEARCHING...\r41 00 BE\r\r>"))
    }
}
