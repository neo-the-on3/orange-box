package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.ObdConnectionManager
import io.github.neotheon3.orangebox.core.obd.pid.ObdPid
import io.github.neotheon3.orangebox.core.obd.pid.PidPoller
import io.github.neotheon3.orangebox.core.obd.pid.VehicleSample
import io.github.neotheon3.orangebox.core.simulator.SimulatedElm327Transport
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user's overspeed threshold (Settings) drives the event, its hysteresis and the snapshot flag. */
@OptIn(ExperimentalCoroutinesApi::class)
class OverspeedSettingTest {
    private var t = 0L
    private fun TelemetryEngine.at(kph: Double) = onSample(VehicleSample(++t * 1_000, mapOf(ObdPid.SPEED to kph, ObdPid.RPM to 2500.0)))

    @Test fun `default threshold is 120 with release below 117`() {
        val e = TelemetryEngine()
        assertFalse(e.at(120.0).instant.overspeed)
        assertTrue(e.at(121.0).instant.overspeed)
        assertTrue(e.at(117.0).instant.overspeed)
        assertFalse(e.at(116.0).instant.overspeed)
        assertEquals(1, e.at(110.0).trip.overspeedCount)
    }

    @Test fun `a lower setting starts at threshold and ends below threshold minus 3`() {
        val e = TelemetryEngine().apply { overspeedKph = 90.0 }
        assertFalse(e.at(90.0).instant.overspeed)
        assertTrue(e.at(91.0).instant.overspeed)
        assertTrue(e.at(88.0).instant.overspeed) // above 87: still on
        assertTrue(e.at(87.0).instant.overspeed)
        assertFalse(e.at(86.0).instant.overspeed)
        assertTrue(e.at(95.0).instant.overspeed)
        assertEquals(2, e.at(50.0).trip.overspeedCount)
    }

    @Test fun `a higher setting ignores speeds the default would flag`() {
        val e = TelemetryEngine().apply { overspeedKph = 140.0 }
        assertFalse(e.at(130.0).instant.overspeed)
        assertTrue(e.at(141.0).instant.overspeed)
    }

    @Test fun `changing the setting applies from the next sample`() {
        val e = TelemetryEngine()
        assertFalse(e.at(110.0).instant.overspeed)
        e.overspeedKph = 100.0
        assertTrue(e.at(110.0).instant.overspeed)
    }

    @Test fun `LiveTelemetry follows the settings flow`() = runTest {
        val controller = SimulatorController(SimulatorState(speedKph = 105, rpm = 2600, throttlePercent = 25))
        val manager = ObdConnectionManager(backgroundScope, { SimulatedElm327Transport(controller) }, heartbeatMillis = 60_000)
        val setting = MutableStateFlow(120.0)
        val live = LiveTelemetry(backgroundScope, manager, PidPoller(manager, clock = { currentTime }), overspeedKph = setting)
        manager.connect()
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertFalse(live.latest.value!!.instant.overspeed)
        setting.value = 100.0
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertTrue(live.latest.value!!.instant.overspeed)
        manager.disconnect(); runCurrent()
    }
}
