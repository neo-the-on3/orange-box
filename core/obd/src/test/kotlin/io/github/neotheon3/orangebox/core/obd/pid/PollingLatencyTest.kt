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
import org.junit.Assert.assertTrue
import org.junit.Test

/** #193: speed refresh and staleness with a dongle that takes 100 ms per request (virtual time). */
@OptIn(ExperimentalCoroutinesApi::class)
class PollingLatencyTest {
    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327", ObdProtocol.CAN_11_500, 12.6))

    private class Conn(val session: Elm327Session) : ObdConnection {
        override val state = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Idle)
        override fun connect() = Unit
        override fun retry() = Unit
        override fun disconnect() = Unit
        override suspend fun <T> withSession(block: suspend (Elm327Session) -> T): T? =
            if (state.value is ObdConnectionState.Connected) block(session) else null
    }

    /** All mode 01 PIDs in 01–20 we poll: fast 04 0C 0D 10 11 03, slow 05 06 07 0A 0B 0F 14 1F(not polled). */
    private val supported = setOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x0A, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x14)

    private fun bitmap(pids: Set<Int>): String {
        var bits = 0L
        pids.forEach { bits = bits or (1L shl (32 - it)) }
        return "%08X".format(bits)
    }

    private fun transport(latency: Long) = FakeObdTransport(responder = { cmd ->
        when {
            cmd == "0100" -> "4100" + bitmap(supported)
            cmd.startsWith("01") && cmd.length == 4 -> {
                val pid = cmd.substring(2).toInt(16)
                if (pid in supported) "41%02X%s".format(pid, "3C".repeat(ObdPid.of(pid)!!.bytes)) else "NO DATA"
            }
            else -> FakeObdTransport.defaultResponse(cmd)
        }
    }).apply { latencyMillis = latency }

    private val logged = mutableListOf<String>()
    private val previousSink = OrangeLog.sink

    @After fun restore() { OrangeLog.sink = previousSink }

    @Test fun `100 ms per request - speed refreshes at least every 700 ms with no slow-tick freeze`() = runTest {
        val t = transport(100)
        val conn = Conn(Elm327Session(t))
        val poller = PidPoller(conn, clock = { currentTime })
        val emittedAt = mutableListOf<Long>()
        val samples = mutableListOf<VehicleSample>()
        val job = launch { poller.samples.collect { samples += it; emittedAt += currentTime } }
        conn.state.value = connected
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        job.cancel()

        val gaps = samples.zipWithNext { a, b -> b.timestampMillis - a.timestampMillis }.drop(1) // after the full first tick
        assertTrue("got ${samples.size}", samples.size > 80)
        assertTrue("p95 ${PollingStats.percentile(gaps, 95)}", PollingStats.percentile(gaps, 95) <= 700)
        assertTrue("max gap ${gaps.max()}", gaps.max() <= 800)
        // Published right after speed and rpm: speed is at most two requests old when the sample arrives.
        val ages = samples.indices.drop(1).map { emittedAt[it] - samples[it].timestampMillis }
        assertTrue("speed age ${ages.max()}", ages.max() <= 200)
        // Every slow PID is still refreshed (once every 10 ticks).
        listOf("0105", "0106", "0107", "010A", "010B", "010F", "0114").forEach { cmd ->
            assertTrue(cmd, t.written.count { it == cmd } >= samples.size / 10 - 1)
        }
    }

    @Test fun `fast dongle keeps the 500 ms period measured from the tick start`() = runTest {
        val t = transport(20)
        val conn = Conn(Elm327Session(t))
        val poller = PidPoller(conn, clock = { currentTime })
        val samples = mutableListOf<VehicleSample>()
        val job = launch { poller.samples.collect { samples += it } }
        conn.state.value = connected
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        job.cancel()
        val gaps = samples.zipWithNext { a, b -> b.timestampMillis - a.timestampMillis }.drop(1)
        assertTrue(gaps.toString(), gaps.all { it == 500L })
    }

    @Test fun `slow PIDs are spread one per tick, not all on one tick`() {
        val poller = PidPoller(Conn(Elm327Session(FakeObdTransport())))
        val slow = PollingConfig().slow
        val perTick = (0L until 10L).map { poller.slowDue(slow, it).size }
        assertTrue(perTick.toString(), perTick.max() <= 2)
        assertEquals(slow.toSet(), (0L until 10L).flatMap { poller.slowDue(slow, it) }.toSet())
        assertEquals(slow.size, perTick.sum())
    }

    @Test fun `polling stats are logged to the connection log`() = runTest {
        OrangeLog.sink = OrangeLog.Sink { _, _, message, _ -> logged += message }
        val t = transport(100)
        val conn = Conn(Elm327Session(t))
        val poller = PidPoller(conn, PollingConfig(statsIntervalMillis = 10_000), clock = { currentTime })
        val job = launch { poller.samples.collect { } }
        conn.state.value = connected
        runCurrent(); advanceTimeBy(25_000); runCurrent()
        job.cancel()
        val stats = logged.filter { it.startsWith("polling_stats") }
        assertEquals(stats.toString(), 2, stats.size)
        assertTrue(stats[0], stats[0].contains("request_ms_p50=100"))
        assertTrue(stats[0], stats[0].contains("sample_gap_ms_p95="))
    }
}
