package io.github.neotheon3.orangebox.core.obd.pid

import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.FakeObdTransport
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Polling against a synthetic SX4-like ECU, timed from Kiran's second RDF1 drive log (build 95): data replies
 * take ~125 ms, NO DATA ~280 ms (the adapter's timeout); supported mode 01 PIDs 01 03 04 05 06 07 0C 0D 0F 10 11
 * 14 21 33 42 49 (MAF yes; no MAP, fuel level or fuel rate). No values from the real log are used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Sx4PollingTest {
    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 13.1))

    private class Conn(val session: Elm327Session) : ObdConnection {
        override val state = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Idle)
        override fun connect() = Unit
        override fun retry() = Unit
        override fun disconnect() = Unit
        override suspend fun <T> withSession(block: suspend (Elm327Session) -> T): T? =
            if (state.value is ObdConnectionState.Connected) block(session) else null
    }

    private val supported = setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x14, 0x20, 0x21, 0x33, 0x40, 0x42, 0x49)

    private fun bitmap(base: Int): String {
        var bits = 0L
        supported.filter { it in base + 1..base + 32 }.forEach { bits = bits or (1L shl (32 - (it - base))) }
        return "%08X".format(bits)
    }

    /** True while the ignition is on; off → every mode 01 request is NO DATA. */
    private var ignition = true

    private val transport = object {
        val t = FakeObdTransport(responder = { cmd ->
            when {
                cmd == "0100" || cmd == "0120" || cmd == "0140" -> "41" + cmd.substring(2) + bitmap(cmd.substring(2).toInt(16))
                cmd.startsWith("01") && cmd.length == 4 -> {
                    val pid = cmd.substring(2).toInt(16)
                    if (ignition && pid in supported) "41%02X%s".format(pid, "3C".repeat(ObdPid.of(pid)?.bytes ?: 4)) else "NO DATA"
                }
                else -> FakeObdTransport.defaultResponse(cmd)
            }
        })
    }.t

    /** ~125 ms for data, ~280 ms for NO DATA, like the SX4 log. */
    private val latency = Elm327SessionLatency()

    private inner class Elm327SessionLatency {
        fun install() {
            val inner = transport.responder
            transport.responder = { cmd -> inner(cmd).also { transport.latencyMillis = if (it == "NO DATA") 280 else 125 } }
        }
    }

    private val logged = mutableListOf<String>()
    private val previousSink = OrangeLog.sink

    @After fun restore() { OrangeLog.sink = previousSink }

    @Test fun `125 ms requests - speed and rpm still refresh every 500 ms, the rest within a few seconds`() = runTest {
        latency.install()
        val conn = Conn(Elm327Session(transport))
        val poller = PidPoller(conn, clock = { currentTime })
        val samples = mutableListOf<VehicleSample>()
        val job = launch { poller.samples.collect { samples += it } }
        conn.state.value = connected
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        job.cancel()

        val gaps = samples.zipWithNext { a, b -> b.timestampMillis - a.timestampMillis }.drop(1)
        assertTrue("p95 ${PollingStats.percentile(gaps, 95)}", PollingStats.percentile(gaps, 95) <= 520)
        assertTrue("max ${gaps.max()}", gaps.max() <= 650) // build 95: 1.3 s typical, 2.8 s on slow ticks
        // The other fast PIDs (MAF, load, throttle, pedal, fuel system) at least every ~2 s.
        listOf("0110", "0104", "0111", "0149", "0103").forEach { cmd ->
            assertTrue("$cmd ${transport.written.count { it == cmd }}", transport.written.count { it == cmd } >= 30)
        }
        // Every slow PID at least every ~20 s.
        listOf("0105", "0106", "0107", "010F", "0114", "0121", "0133", "0142").forEach { cmd ->
            assertTrue("$cmd ${transport.written.count { it == cmd }}", transport.written.count { it == cmd } >= 3)
        }
        // Unsupported PIDs are never polled.
        listOf("010B", "012F", "015E", "0146").forEach { cmd -> assertEquals(cmd, 0, transport.written.count { it == cmd }) }
    }

    @Test fun `ignition off - values are dropped, the trip sees no rpm, and polling backs off until the ECU answers`() = runTest {
        OrangeLog.sink = OrangeLog.Sink { _, _, message, _ -> logged += message }
        latency.install()
        val conn = Conn(Elm327Session(transport))
        val poller = PidPoller(conn, clock = { currentTime })
        val samples = mutableListOf<VehicleSample>()
        val job = launch { poller.samples.collect { samples += it } }
        conn.state.value = connected
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertTrue(samples.last()[ObdPid.RPM] != null)

        ignition = false
        advanceTimeBy(5_000); runCurrent()
        // Build 95 repeated the last idle rpm and MAF here for hours.
        assertNull("rpm dropped", samples.last()[ObdPid.RPM])
        assertNull("MAF dropped", samples.last()[ObdPid.MAF])
        assertTrue(logged.toString(), logged.any { it.startsWith("ecu_silent") })

        val before = transport.written.size
        advanceTimeBy(60_000); runCurrent()
        val perMinute = transport.written.size - before
        assertTrue("requests/min while silent: $perMinute", perMinute <= 2 * 60 / 2 + 2) // speed+rpm every ~2.5 s
        assertTrue(samples.takeLast(10).all { it.values.isEmpty() })

        ignition = true
        advanceTimeBy(5_000); runCurrent()
        job.cancel()
        assertTrue(logged.toString(), logged.any { it.startsWith("ecu_awake") })
        assertTrue(samples.last()[ObdPid.RPM] != null)
        assertTrue(samples.last()[ObdPid.MAF] != null)
    }

    @Test fun `a single NO DATA keeps the value, two in a row drop it`() = runTest {
        var flaky = 0
        val inner = transport.responder
        transport.responder = { cmd -> if (cmd == "010D" && flaky-- > 0) "NO DATA" else inner(cmd) }
        val conn = Conn(Elm327Session(transport))
        val poller = PidPoller(conn, clock = { currentTime })
        val samples = mutableListOf<VehicleSample>()
        val job = launch { poller.samples.collect { samples += it } }
        conn.state.value = connected
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        flaky = 1
        advanceTimeBy(500); runCurrent()
        assertTrue("one miss keeps speed", samples.last()[ObdPid.SPEED] != null)
        flaky = 2
        advanceTimeBy(1_000); runCurrent()
        job.cancel()
        assertTrue("two misses drop speed", samples.any { it.timestampMillis > 2_500 && it[ObdPid.SPEED] == null })
    }
}
