package io.github.neotheon3.orangebox.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PetrolBlendTest {
    @Test fun `E20 in India, E0 elsewhere or unknown (#208)`() {
        assertEquals(PetrolBlend.E20, PetrolBlend.forCountry("IN"))
        assertEquals(PetrolBlend.E20, PetrolBlend.forCountry("in"))
        assertEquals(PetrolBlend.E0, PetrolBlend.forCountry("DE"))
        assertEquals(PetrolBlend.E0, PetrolBlend.forCountry(null))
        assertEquals(20, PetrolBlend.E20.ethanolPercent)
    }

    @Test fun `speedometer offset is display only and clamped (#210)`() {
        assertEquals(105.0, DashboardPreferences(speedometerOffsetPercent = 5).displaySpeed(100.0), 1e-9)
        assertEquals(100.0, DashboardPreferences().displaySpeed(100.0), 1e-9)
        assertEquals(10, DashboardPreferences.clampSpeedometerOffset(25))
        assertEquals(0, DashboardPreferences.clampSpeedometerOffset(-3))
    }
}
