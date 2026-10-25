package io.github.neotheon3.orangebox.core.driving.alerts

import io.github.neotheon3.orangebox.core.model.AlertKind
import io.github.neotheon3.orangebox.core.model.AlertPreferences
import io.github.neotheon3.orangebox.core.model.AppPreferences
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.telemetry.InstantTelemetry
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.TripTotals
import io.github.neotheon3.orangebox.core.testing.FakeAppPreferencesRepository
import io.github.neotheon3.orangebox.core.testing.FakeObdConnection
import io.github.neotheon3.orangebox.core.testing.FakeTelemetrySource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertMonitorTest {
    private val connected = ObdConnectionState.Connected(Elm327Info("ELM327 v1.5", ObdProtocol.CAN_11_500, 14.1))
    private val played = mutableListOf<Triple<AlertKind, Boolean, Boolean>>()
    private val output = object : AlertOutput {
        override fun play(kind: AlertKind, sound: Boolean, voice: Boolean) { played += Triple(kind, sound, voice) }
    }
    private var t = 0L

    private fun snap(overspeed: Boolean = false, mil: Boolean? = false) = TelemetrySnapshot(
        t++,
        InstantTelemetry(
            speedKph = 60.0, rpm = 1500.0, coolantC = 85.0, fuelLevelPercent = null, voltage = 14.0, fuelFlowLph = 1.0,
            economyLPer100Km = null, accelMps2 = null, fuelCut = false, idling = false, eco = false, overspeed = overspeed,
            warnings = emptySet(), milOn = mil,
        ),
        TripTotals(),
    )

    private class Rig(val conn: FakeObdConnection, val telemetry: FakeTelemetrySource, val prefs: FakeAppPreferencesRepository)

    private fun TestScope.rig(alerts: AlertPreferences = AlertPreferences()): Rig {
        val r = Rig(FakeObdConnection(), FakeTelemetrySource(), FakeAppPreferencesRepository(AppPreferences(alerts = alerts)))
        AlertMonitor(backgroundScope, r.conn, r.telemetry, r.prefs, output) { testScheduler.currentTime + t * 1_000_000 }.install()
        runCurrent()
        return r
    }

    @Test fun `alerts play only while connected and only when enabled`() = runTest {
        val r = rig()
        r.telemetry.latest.value = snap(overspeed = true); runCurrent()
        assertEquals("not connected", emptyList<Any>(), played)
        r.conn.state.value = connected; runCurrent()
        assertEquals(listOf(Triple(AlertKind.OVERSPEED, true, false)), played)
        r.prefs.setAlerts(AlertPreferences(mil = false, voice = true)); runCurrent()
        r.telemetry.latest.value = snap(mil = true); runCurrent()
        assertEquals("MIL is off", 1, played.size)
        r.telemetry.latest.value = snap(overspeed = true); runCurrent()
        assertEquals(Triple(AlertKind.OVERSPEED, true, true), played.last())
    }

    @Test fun `muted sound and voice plays nothing`() = runTest {
        val r = rig(AlertPreferences(sound = false, voice = false))
        r.conn.state.value = connected; runCurrent()
        r.telemetry.latest.value = snap(overspeed = true, mil = true); runCurrent()
        assertEquals(emptyList<Any>(), played)
    }

    @Test fun `a reconnect starts a fresh policy`() = runTest {
        val r = rig()
        r.conn.state.value = connected; runCurrent()
        r.telemetry.latest.value = snap(mil = true); runCurrent()
        r.conn.state.value = ObdConnectionState.Idle; runCurrent()
        r.conn.state.value = connected; runCurrent()
        r.telemetry.latest.value = snap(mil = true); runCurrent()
        assertEquals(listOf(AlertKind.MIL, AlertKind.MIL), played.map { it.first })
    }
}
