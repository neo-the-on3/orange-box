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
}
