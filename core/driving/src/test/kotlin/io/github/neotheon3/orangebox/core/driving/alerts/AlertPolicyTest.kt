package io.github.neotheon3.orangebox.core.driving.alerts

import io.github.neotheon3.orangebox.core.model.AlertKind
import io.github.neotheon3.orangebox.core.model.AlertPreferences
import io.github.neotheon3.orangebox.core.telemetry.InstantTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {
    private fun i(
        overspeed: Boolean = false, harshAccel: Boolean = false, harshBrake: Boolean = false,
        idling: Boolean = false, mil: Boolean? = false,
    ) = InstantTelemetry(
        speedKph = if (idling) 0.0 else 60.0, rpm = 1500.0, coolantC = 85.0, fuelLevelPercent = null, voltage = 14.0,
        fuelFlowLph = 1.0, economyLPer100Km = null, accelMps2 = null, fuelCut = false, idling = idling, eco = false,
        overspeed = overspeed, warnings = emptySet(), harshAccel = harshAccel, harshBrake = harshBrake, milOn = mil,
    )

    @Test fun `overspeed alerts when it starts, not while it lasts, and honours the cooldown`() {
        val p = AlertPolicy()
        assertEquals(emptyList<AlertKind>(), p.onSample(i(), 0))
        assertEquals(listOf(AlertKind.OVERSPEED), p.onSample(i(overspeed = true), 1_000))
        assertEquals("still overspeeding", emptyList<AlertKind>(), p.onSample(i(overspeed = true), 30_000))
        p.onSample(i(), 31_000)
        assertEquals("starts again inside the 60 s cooldown", emptyList<AlertKind>(), p.onSample(i(overspeed = true), 40_000))
        p.onSample(i(), 70_000)
        assertEquals(listOf(AlertKind.OVERSPEED), p.onSample(i(overspeed = true), 71_000))
    }

    @Test fun `harsh acceleration and braking alert separately with a 10 s cooldown each`() {
        val p = AlertPolicy()
        assertEquals(listOf(AlertKind.HARSH_ACCEL), p.onSample(i(harshAccel = true), 0))
        assertEquals(listOf(AlertKind.HARSH_BRAKE), p.onSample(i(harshBrake = true), 2_000))
        p.onSample(i(), 3_000)
        assertEquals(emptyList<AlertKind>(), p.onSample(i(harshBrake = true), 5_000))
        p.onSample(i(), 6_000)
        assertEquals(listOf(AlertKind.HARSH_BRAKE), p.onSample(i(harshBrake = true), 12_500))
    }

    @Test fun `idling alerts once after 3 minutes, again only after moving`() {
        val p = AlertPolicy()
        assertEquals(emptyList<AlertKind>(), p.onSample(i(idling = true), 0))
        assertEquals(emptyList<AlertKind>(), p.onSample(i(idling = true), 179_000))
        assertEquals(listOf(AlertKind.IDLING), p.onSample(i(idling = true), 180_000))
        assertEquals(emptyList<AlertKind>(), p.onSample(i(idling = true), 400_000))
        p.onSample(i(), 401_000)
        assertEquals(emptyList<AlertKind>(), p.onSample(i(idling = true), 402_000))
        assertEquals(listOf(AlertKind.IDLING), p.onSample(i(idling = true), 582_000))
    }

    @Test fun `check-engine lamp alerts once per connection, unknown does not count`() {
        val p = AlertPolicy()
        assertEquals(emptyList<AlertKind>(), p.onSample(i(mil = null), 0))
        assertEquals(listOf(AlertKind.MIL), p.onSample(i(mil = true), 1_000))
        p.onSample(i(mil = false), 2_000)
        assertEquals(emptyList<AlertKind>(), p.onSample(i(mil = true), 3_000))
        assertEquals("a new connection is a new policy", listOf(AlertKind.MIL), AlertPolicy().onSample(i(mil = true), 0))
    }

    @Test fun `several alerts can start on the same sample`() {
        assertEquals(
            listOf(AlertKind.OVERSPEED, AlertKind.HARSH_ACCEL, AlertKind.MIL),
            AlertPolicy().onSample(i(overspeed = true, harshAccel = true, mil = true), 0),
        )
    }

    @Test fun `preferences toggle each alert, and nothing is audible without sound or voice`() {
        val d = AlertPreferences()
        assertTrue(d.isEnabled(AlertKind.OVERSPEED)); assertTrue("idling on by default", d.isEnabled(AlertKind.IDLING))
        assertFalse(d.copy(idling = false).isEnabled(AlertKind.IDLING))
        assertTrue(d.isEnabled(AlertKind.HARSH_ACCEL)); assertTrue(d.isEnabled(AlertKind.HARSH_BRAKE)); assertTrue(d.isEnabled(AlertKind.MIL))
        assertFalse(d.copy(harsh = false).isEnabled(AlertKind.HARSH_BRAKE))
        assertTrue(d.audible)
        assertFalse(d.copy(sound = false, voice = false).audible)
        assertTrue(d.copy(sound = false, voice = true).audible)
        assertFalse(AlertPreferences(overspeed = false, idling = false, harsh = false, mil = false).audible)
    }
}
