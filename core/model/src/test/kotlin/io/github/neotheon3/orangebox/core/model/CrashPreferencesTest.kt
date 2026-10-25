package io.github.neotheon3.orangebox.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashPreferencesTest {
    @Test fun `contact numbers are normalized`() {
        assertEquals("+919876543210", CrashPreferences.normalizeContactNumber(" +91 98765-43210 "))
        assertEquals("02012345678", CrashPreferences.normalizeContactNumber("(020) 1234.5678"))
        assertEquals("00441234567890", CrashPreferences.normalizeContactNumber("0044 1234 567890"))
        assertEquals("112", CrashPreferences.normalizeContactNumber("112"))
        assertEquals("15 digits is the E.164 maximum", "+123456789012345", CrashPreferences.normalizeContactNumber("+123456789012345"))
    }

    @Test fun `invalid contact numbers are rejected`() {
        for (bad in listOf(null, "", "   ", "+", "12", "+12", "1234567890123456", "98765abc10", "++91987", "+91 98*65", "tel:12345", "9876 5432#")) {
            assertNull(bad, CrashPreferences.normalizeContactNumber(bad))
        }
    }

    @Test fun `threshold and countdown are clamped`() {
        assertEquals(CrashPreferences.MIN_THRESHOLD_G, CrashPreferences.clampThreshold(1.0), 0.0)
        assertEquals(CrashPreferences.MAX_COUNTDOWN_S, CrashPreferences.clampCountdown(600))
    }
}
