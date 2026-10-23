package io.github.neotheon3.orangebox

import io.github.neotheon3.orangebox.core.model.BackendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchExtrasTest {
    @Test fun `valid backend_mode values resolve`() {
        assertEquals(BackendMode.OFF, LaunchExtras.resolveBackendMode("off"))
        assertEquals(BackendMode.FAKE, LaunchExtras.resolveBackendMode("fake"))
        assertEquals(BackendMode.REMOTE, LaunchExtras.resolveBackendMode("remote"))
    }

    @Test fun `invalid values are ignored`() {
        assertNull(LaunchExtras.resolveBackendMode("bogus"))
        assertNull(LaunchExtras.resolveBackendMode(null))
    }

    @Test fun `extra key is stable`() = assertEquals("backend_mode", LaunchExtras.BACKEND_MODE)

    @Test fun `onboarding extra resolves skip and reset only`() {
        assertEquals(LaunchExtras.Onboarding.SKIP, LaunchExtras.resolveOnboarding("skip"))
        assertEquals(LaunchExtras.Onboarding.RESET, LaunchExtras.resolveOnboarding(" Reset "))
        assertNull(LaunchExtras.resolveOnboarding("later"))
        assertNull(LaunchExtras.resolveOnboarding(null))
        assertEquals("onboarding", LaunchExtras.ONBOARDING)
    }

    @Test fun `vehicle maps to the telemetry profile`() {
        val p = VehicleProfiles.of(
            io.github.neotheon3.orangebox.core.model.Vehicle(
                name = "Van", fuel = io.github.neotheon3.orangebox.core.model.VehicleFuel.DIESEL, displacementCc = 2184,
            ),
        )
        assertEquals(io.github.neotheon3.orangebox.core.obd.pid.FuelType.DIESEL, p.fuelType)
        assertEquals(2.184, p.displacementLitres!!, 1e-9)
    }
}
