package io.github.neotheon3.orangebox.core.telemetry

import io.github.neotheon3.orangebox.core.obd.ObdConnectionManager
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.pid.PidPoller
import io.github.neotheon3.orangebox.core.simulator.SimulatedElm327Transport
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTelemetryTest {
    @Test fun `end to end simulator to snapshot, cleared on disconnect`() = runTest {
        val controller = SimulatorController(SimulatorState(speedKph = 72, rpm = 2600, throttlePercent = 25))
        val manager = ObdConnectionManager(backgroundScope, { SimulatedElm327Transport(controller) }, heartbeatMillis = 60_000)
        val live = LiveTelemetry(backgroundScope, manager, PidPoller(manager, clock = { currentTime }))
        manager.connect()
        runCurrent(); advanceTimeBy(3_000); runCurrent()
        assertTrue(manager.state.value is ObdConnectionState.Connected)
        val snap = live.latest.value!!
        assertEquals(72.0, snap.instant.speedKph!!, 0.0)
        assertEquals(2600.0, snap.instant.rpm!!, 0.0)
        assertTrue(snap.instant.fuelFlowLph!! > 0)
        assertTrue(snap.trip.distanceKm > 0.04) // ≈ 72 km/h · 2.5 s
        manager.disconnect()
        runCurrent()
        assertNull(live.latest.value)
    }
}
