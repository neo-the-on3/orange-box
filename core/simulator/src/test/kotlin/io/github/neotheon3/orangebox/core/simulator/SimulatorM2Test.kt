package io.github.neotheon3.orangebox.core.simulator

import io.github.neotheon3.orangebox.core.obd.DtcKind
import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.VinDecoder
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorM2Test {
    private suspend fun session(c: SimulatorController) =
        Elm327Session(SimulatedElm327Transport(c).also { it.open() }).also { it.initialize() }

    @Test fun `supported bitmaps chain 00-20-40 and list exactly the simulated PIDs`() = runTest {
        val s = session(SimulatorController()).readSupportedPids()
        assertEquals(SimulatedElm327.SUPPORTED.toSet(), s.all)
    }

    @Test fun `decoded values follow the state`() = runTest {
        val c = SimulatorController(SimulatorState(speedKph = 80, rpm = 2880, throttlePercent = 30, fuelPercent = 40, coolantC = 90))
        val s = session(c)
        assertEquals(80.0, s.read(ObdPid.SPEED)!!, 0.0)
        assertEquals(2880.0, s.read(ObdPid.RPM)!!, 0.0)
        assertEquals(40.0, s.read(ObdPid.FUEL_LEVEL)!!, 0.5)
        assertEquals(90.0, s.read(ObdPid.COOLANT_TEMP)!!, 0.0)
        assertEquals(14.1, s.read(ObdPid.CONTROL_MODULE_VOLTAGE)!!, 0.001)
        assertEquals(1.0, s.read(ObdPid.FUEL_TYPE)!!, 0.0)
        assertTrue(s.read(ObdPid.MAF)!! > 10)
        assertNull(s.read(ObdPid.FUEL_RATE)) // unsupported → NO DATA
    }

    @Test fun `coasting above 20 kmh reports decel fuel cut`() = runTest {
        val c = SimulatorController(SimulatorState(speedKph = 60, rpm = 2160, throttlePercent = 0))
        assertEquals(4.0, session(c).read(ObdPid.FUEL_SYSTEM_STATUS)!!, 0.0)
    }

    @Test fun `stored, pending and permanent DTCs incl multi-frame and clear`() = runTest {
        val c = SimulatorController()
        val s = session(c)
        listOf("P0133", "P0420", "U0123").forEach { c.injectDtc(it) } // 3 codes → multi-frame
        c.addPendingDtc("P0300")
        assertEquals(listOf("P0133", "P0420", "U0123"), s.readDtcs(DtcKind.STORED))
        assertEquals(listOf("P0300"), s.readDtcs(DtcKind.PENDING))
        assertEquals(emptyList<String>(), s.readDtcs(DtcKind.PERMANENT))
        assertEquals(3, s.readMonitorStatus()!!.dtcCount)
        assertTrue(s.clearDtcs())
        assertEquals(emptyList<String>(), s.readDtcs(DtcKind.STORED))
        assertEquals(emptyList<String>(), s.readDtcs(DtcKind.PENDING))
        assertTrue(!s.readMonitorStatus()!!.milOn)
    }

    @Test fun `VIN over multi-frame CAN`() = runTest {
        assertEquals(VinDecoder.Result.Valid(SimulatorState.VIN, checkDigitValid = true), session(SimulatorController()).readVin())
    }

    @Test fun `script frames and analytic distance`() {
        val f = DriveScript.CITY.frameAt(5.0)
        assertEquals(25.0, f.speedKph, 1e-9)
        assertTrue(f.throttlePercent > 0)
        assertEquals(0, DriveScript.CITY.frameAt(34.0).throttlePercent) // decelerating
        // city: 3 × (10 s 0→50 + 20 s at 50 + 8 s 50→0) = 3 × (25·10 + 50·20 + 25·8)/3600 km
        assertEquals(3 * (250.0 + 1000 + 200) / 3600, DriveScript.CITY.distanceKm(), 1e-9)
        assertEquals(SimulatorState.IDLE_RPM, DriveScript.gearRpm(0.0))
    }

    @Test fun `player drives the controller, stop coasts to idle`() = runTest {
        val c = SimulatorController()
        val p = DriveScriptPlayer(c, backgroundScope, tickMillis = 250)
        p.start(DriveScript.HIGHWAY)
        runCurrent()
        assertEquals("highway", c.state.value.runningScript)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(100, c.state.value.speedKph)
        p.stop()
        runCurrent()
        assertNull(c.state.value.runningScript)
        assertEquals(0, c.state.value.throttlePercent)
        advanceTimeBy(5_000); runCurrent()
        // 100 km/h − 7.2 km/h/s · 5 s ≈ 64 km/h: still coasting, decelerating smoothly
        assertTrue(c.state.value.speedKph in 60..68)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(0, c.state.value.speedKph)
        assertEquals(SimulatorState.IDLE_RPM, c.state.value.rpm)
    }

    @Test fun `script that ends on its own also coasts to a stop`() = runTest {
        val c = SimulatorController()
        val p = DriveScriptPlayer(c, backgroundScope, tickMillis = 250)
        p.start(DriveScript.HIGHWAY) // ends at 100 km/h
        advanceTimeBy((DriveScript.HIGHWAY.durationS * 1000).toLong() + 500); runCurrent()
        assertNull(c.state.value.runningScript)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, c.state.value.speedKph)
        assertEquals(SimulatorState.IDLE_RPM, c.state.value.rpm)
    }

    @Test fun `starting a new script cancels the coast`() = runTest {
        val c = SimulatorController()
        val p = DriveScriptPlayer(c, backgroundScope, tickMillis = 250)
        p.start(DriveScript.HIGHWAY); advanceTimeBy(15_000); runCurrent()
        p.stop(); advanceTimeBy(1_000)
        p.start(DriveScript.IDLE); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, c.state.value.speedKph)
        assertEquals("idle", c.state.value.runningScript)
    }

    @Test fun `permanent DTCs survive mode 04 and VIN can be changed`() = runTest {
        val c = SimulatorController()
        val s = session(c)
        assertTrue(c.addDtc("P0420", DtcList.PERMANENT))
        assertTrue(!c.addDtc("XYZ12", DtcList.PENDING))
        assertEquals(listOf("P0420"), s.readDtcs(DtcKind.PERMANENT))
        s.clearDtcs()
        assertEquals(listOf("P0420"), s.readDtcs(DtcKind.PERMANENT))
        c.setVin("1M8GDM9AXKP042788")
        assertEquals(VinDecoder.Result.Valid("1M8GDM9AXKP042788", true), s.readVin())
        c.setVin("BAD")
        assertTrue(s.readVin() is VinDecoder.Result.Invalid)
    }
}
