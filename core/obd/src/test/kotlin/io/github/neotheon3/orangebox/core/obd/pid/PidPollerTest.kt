package io.github.neotheon3.orangebox.core.obd.pid

import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.FakeObdTransport
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PidPollerTest {
    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327", ObdProtocol.CAN_11_500, 12.6))

    private class Conn(var session: Elm327Session) : ObdConnection {
        override val state = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Idle)
        override fun connect() = Unit
        override fun retry() = Unit
        override fun disconnect() = Unit
        override suspend fun <T> withSession(block: suspend (Elm327Session) -> T): T? =
            if (state.value is ObdConnectionState.Connected) block(session) else null
    }

    /** Supports RPM, speed and coolant only (0100 → 0C, 0D, 05). */
    private fun transport() = FakeObdTransport(responder = {
        when (it) {
            "0100" -> "4100" + "08180000"
            "010C" -> "410C0C80"
            "010D" -> "410D32"
            "0105" -> "41057B"
            else -> if (it.startsWith("01")) error("unsupported PID $it polled") else FakeObdTransport.defaultResponse(it)
        }
    })

    @Test fun `bitmap 08180000 means 05, 0C, 0D`() {
        assertEquals(setOf(0x05, 0x0C, 0x0D), SupportedPids.decodeBitmap(0, intArrayOf(0x08, 0x18, 0x00, 0x00)))
    }

    @Test fun `fast every 500 ms, slow every 10th tick, unsupported never polled`() = runTest {
        val t = transport()
        val conn = Conn(Elm327Session(t))
        val poller = PidPoller(conn, PollingConfig(fastIntervalMillis = 500, slowEvery = 10), clock = { currentTime })
        val samples = mutableListOf<VehicleSample>()
        val job = launch { poller.samples.collect { samples += it } }
        conn.state.value = connected
        runCurrent()
        advanceTimeBy(10_000 - 1)
        runCurrent()
        job.cancel()
        assertEquals(20, samples.size) // t = 0, 500, …, 9500
        assertEquals((0 until 20).map { it * 500L }, samples.map { it.timestampMillis })
        assertEquals(20, t.written.count { it == "010C" })
        assertEquals(20, t.written.count { it == "010D" })
        assertEquals(2, t.written.count { it == "0105" }) // ticks 0 and 10
        assertEquals(setOf(ObdPid.RPM, ObdPid.SPEED, ObdPid.COOLANT_TEMP), poller.activePids)
        val last = samples.last()
        assertEquals(800.0, last[ObdPid.RPM]!!, 0.0)
        assertEquals(50.0, last[ObdPid.SPEED]!!, 0.0)
        assertEquals(83.0, last[ObdPid.COOLANT_TEMP]!!, 0.0)
    }

    @Test fun `MIL state and DTC count read on slow ticks when PID 01 is supported`() = runTest {
        // 0100 → 01, 0C (bits 1 and 12): 0x80100000
        val t = FakeObdTransport(responder = {
            when (it) {
                "0100" -> "4100" + "80100000"
                "0101" -> "4101" + "82076500" // MIL on, 2 DTCs
                "010C" -> "410C0C80"
                else -> if (it.startsWith("01")) error("unsupported PID $it polled") else FakeObdTransport.defaultResponse(it)
            }
        })
        val conn = Conn(Elm327Session(t))
        val poller = PidPoller(conn, PollingConfig(fastIntervalMillis = 500, slowEvery = 10), clock = { currentTime })
        val samples = mutableListOf<VehicleSample>()
        val job = launch { poller.samples.collect { samples += it } }
        conn.state.value = connected
        runCurrent(); advanceTimeBy(5_999); runCurrent()
        job.cancel()
        assertEquals(2, t.written.count { it == "0101" }) // ticks 0 and 10
        assertEquals(io.github.neotheon3.orangebox.core.obd.MonitorStatus(milOn = true, dtcCount = 2), samples.last().monitor)
    }

    @Test fun `stops on disconnect and resumes on reconnect`() = runTest {
        val t = transport()
        val conn = Conn(Elm327Session(t))
        val poller = PidPoller(conn, clock = { currentTime })
        var count = 0
        val job = launch { poller.samples.collect { count++ } }
        conn.state.value = connected
        runCurrent(); advanceTimeBy(1_999); runCurrent()
        assertEquals(4, count)
        conn.state.value = ObdConnectionState.WaitingToRetry(1, 5, 2, ObdError.Io("drop"))
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(4, count)
        val bitmapReads = t.written.count { it == "0100" }
        conn.state.value = connected
        runCurrent(); advanceTimeBy(999); runCurrent()
        assertEquals(6, count)
        assertEquals(bitmapReads + 1, t.written.count { it == "0100" }) // re-reads supported PIDs per session
        job.cancel()
    }

    @Test fun `link failure ends polling quietly`() = runTest {
        val t = transport()
        val conn = Conn(Elm327Session(t))
        val poller = PidPoller(conn, clock = { currentTime })
        var count = 0
        val job = launch { poller.samples.collect { count++ } }
        conn.state.value = connected
        runCurrent()
        t.writeError = ObdError.Io("gone")
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, count)
        assertTrue(job.isActive) // waits for the next Connected
        assertFalse(t.written.any { it == "0110" })
        job.cancel()
    }
}
