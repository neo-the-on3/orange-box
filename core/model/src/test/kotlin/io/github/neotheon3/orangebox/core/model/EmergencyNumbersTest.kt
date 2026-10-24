package io.github.neotheon3.orangebox.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyNumbersTest {
    @Test fun `known countries`() {
        mapOf("IN" to "112", "DE" to "112", "FR" to "112", "US" to "911", "CA" to "911", "GB" to "999", "AU" to "000", "NZ" to "111", "JP" to "110")
            .forEach { (c, n) -> assertEquals(c, n, EmergencyNumbers.forCountry(c)) }
    }

    @Test fun `case-insensitive, unknown and missing fall back to 112`() {
        assertEquals("911", EmergencyNumbers.forCountry("us"))
        assertEquals("112", EmergencyNumbers.forCountry("ZZ"))
        assertEquals("112", EmergencyNumbers.forCountry(null))
        assertEquals("112", EmergencyNumbers.forCountry(""))
    }

    @Test fun `number validation and overspeed clamp`() {
        assertTrue(DashboardPreferences.isValidEmergencyNumber("112"))
        assertTrue(DashboardPreferences.isValidEmergencyNumber("+4915112345"))
        assertFalse(DashboardPreferences.isValidEmergencyNumber("1"))
        assertFalse(DashboardPreferences.isValidEmergencyNumber("11 2"))
        assertEquals(40, DashboardPreferences.clampOverspeed(10))
        assertEquals(200, DashboardPreferences.clampOverspeed(260))
        assertEquals(120, DashboardPreferences().overspeedKph)
    }
}
