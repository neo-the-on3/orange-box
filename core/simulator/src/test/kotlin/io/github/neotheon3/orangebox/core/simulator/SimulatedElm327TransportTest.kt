package io.github.neotheon3.orangebox.core.simulator

import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class SimulatedElm327TransportTest {
    private suspend fun session(c: SimulatorController, random: Random = Random(1)): Elm327Session {
        val t = SimulatedElm327Transport(c, random)
        t.open()
        return Elm327Session(t).also { it.initialize() }
    }

    private fun rpm(b: IntArray) = (b[0] * 256 + b[1]) / 4

    @Test fun `healthy dongle initialises on CAN with voltage`() = runTest {
        val s = session(SimulatorController())
        assertEquals(ObdProtocol.CAN_11_500, s.info!!.protocol)
        assertEquals(14.1, s.info!!.voltage!!, 0.0)
    }

    @Test fun `delay connect holds the link open for the configured seconds`() = runTest {
        val c = SimulatorController(SimulatorState(connectDelaySeconds = SimulatorState.CONNECT_DELAY_SECONDS))
        val t = SimulatedElm327Transport(c)
        val start = testScheduler.currentTime
        t.open()
        assertEquals(10_000L, testScheduler.currentTime - start)
        assertTrue("Delay connect (10 s)" in c.state.value.activeFailures)
        c.update { it.copy(connectDelaySeconds = 0) }
        val again = testScheduler.currentTime
        SimulatedElm327Transport(c).open()
        assertEquals(again, testScheduler.currentTime)
    }

    @Test fun `engine off reports 0 rpm and 12_4 V`() = runTest {
        val c = SimulatorController(SimulatorState(engineOn = false))
        val s = session(c)
        assertEquals(12.4, s.readVoltage()!!, 0.0)
        assertEquals(0, rpm(s.requestPid(0x0C, 2)!!))
        c.update { it.copy(engineOn = true) }
        assertEquals(SimulatorState.IDLE_RPM, rpm(s.requestPid(0x0C, 2)!!))
        assertEquals(14.1, s.readVoltage()!!, 0.0)
    }

    @Test fun `sliders are visible through PIDs`() = runTest {
        val c = SimulatorController(SimulatorState(speedKph = 87, rpm = 2450, coolantC = 92, fuelPercent = 40))
        val s = session(c)
        assertEquals(87, s.requestPid(0x0D)!![0])
        assertEquals(2450, rpm(s.requestPid(0x0C, 2)!!))
        assertEquals(92, s.requestPid(0x05)!![0] - 40)
        assertEquals(40, (s.requestPid(0x2F)!![0] * 100 + 127) / 255)
    }

    @Test fun `dtc inject sets MIL, clear resets`() = runTest {
        val c = SimulatorController()
        val s = session(c)
        assertTrue(c.injectDtc("p0133"))
        assertFalse(c.injectDtc("bogus"))
        assertEquals(listOf("P0133"), s.readDtcs())
        val status = s.readMonitorStatus()!!
        assertTrue(status.milOn); assertEquals(1, status.dtcCount)
        assertTrue(s.clearDtcs())
        assertEquals(emptyList<String>(), s.readDtcs())
        assertFalse(s.readMonitorStatus()!!.milOn)
    }

    @Test fun `MIL toggle alone`() = runTest {
        val c = SimulatorController(SimulatorState(milOn = true))
        assertTrue(session(c).readMonitorStatus()!!.milOn)
    }

    @Test fun `unpaired fails open with NotPaired`() = runTest {
        val t = SimulatedElm327Transport(SimulatorController(SimulatorState(paired = false)))
        try { t.open(); fail() } catch (e: ObdError.NotPaired) { assertTrue(e.isTerminal) }
    }

    @Test fun `connection failure fails open and live writes`() = runTest {
        val c = SimulatorController(SimulatorState(connectionFailure = true))
        try { SimulatedElm327Transport(c).open(); fail() } catch (_: ObdError.Io) {}
        c.update { it.copy(connectionFailure = false) }
        val s = session(c)
        c.update { it.copy(connectionFailure = true) }
        try { s.readVoltage(); fail() } catch (_: ObdError.Io) {}
    }

    @Test fun `unsupported protocol fails init`() = runTest {
        val c = SimulatorController(SimulatorState(unsupportedProtocol = true))
        try { session(c); fail() } catch (_: ObdError.UnsupportedProtocol) {}
    }

    @Test fun `slow adds 1_5 s per response but still succeeds`() = runTest {
        val t = SimulatedElm327Transport(SimulatorController(SimulatorState(slowResponses = true))).also { it.open() }
        val slow = Elm327Session(t)
        val start = currentTime
        assertEquals(14.1, slow.readVoltage()!!, 0.001)
        assertEquals(1_500, currentTime - start)
    }

    @Test fun `garbled corrupts about 30 percent and session drops them`() = runTest {
        val c = SimulatorController()
        val s = session(c, Random(42))
        c.update { it.copy(garbledResponses = true) }
        var dropped = 0
        repeat(200) { if (s.requestPid(0x0C, 2) == null) dropped++ }
        assertTrue("dropped=$dropped", dropped in 40..80)
    }

    @Test fun `reset restores defaults`() {
        val c = SimulatorController(SimulatorState(garbledResponses = true, speedKph = 50))
        c.reset()
        assertEquals(SimulatorState(), c.state.value)
        assertNull(SimulatorState().activeFailures.firstOrNull())
    }
}
