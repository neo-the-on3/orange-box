package io.github.neotheon3.orangebox.core.model

import io.github.neotheon3.orangebox.core.model.BaseUrlValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlValidatorTest {
    @Test fun `accepts https urls`() {
        assertEquals(Result.Valid, BaseUrlValidator.validate("https://api.example.com"))
        assertEquals(Result.Valid, BaseUrlValidator.validate("https://api.example.com:8443/v1/"))
        assertEquals(Result.Valid, BaseUrlValidator.validate("HTTPS://localhost"))
    }

    @Test fun `rejects cleartext`() {
        assertEquals(Result.NotHttps, BaseUrlValidator.validate("http://14.63.216.111:9801"))
    }

    @Test fun `rejects empty and malformed`() {
        assertEquals(Result.Empty, BaseUrlValidator.validate("   "))
        assertEquals(Result.Malformed, BaseUrlValidator.validate("api.example.com"))
        assertEquals(Result.Malformed, BaseUrlValidator.validate("https://"))
        assertEquals(Result.Malformed, BaseUrlValidator.validate("https://user:pw@example.com"))
        assertEquals(Result.Malformed, BaseUrlValidator.validate("https://example.com:99999"))
        assertEquals(Result.Malformed, BaseUrlValidator.validate("https://exa mple.com"))
    }

    @Test fun `remote readiness requires valid url`() {
        assertTrue(BackendConfig(BackendMode.REMOTE, "https://x.example").isRemoteReady)
        assertFalse(BackendConfig(BackendMode.REMOTE, "http://x.example").isRemoteReady)
        assertFalse(BackendConfig(BackendMode.OFF, "https://x.example").isRemoteReady)
    }
}
