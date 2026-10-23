package io.github.neotheon3.orangebox.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendModeTest {
    @Test fun `default is off`() = assertEquals(BackendMode.OFF, BackendMode.DEFAULT)

    @Test fun `parses wire names case-insensitively`() {
        assertEquals(BackendMode.OFF, BackendMode.parse("off"))
        assertEquals(BackendMode.FAKE, BackendMode.parse("FAKE"))
        assertEquals(BackendMode.REMOTE, BackendMode.parse("  Remote "))
    }

    @Test fun `rejects unknown and null values`() {
        assertNull(BackendMode.parse(null))
        assertNull(BackendMode.parse(""))
        assertNull(BackendMode.parse("on"))
        assertNull(BackendMode.parse("remote2"))
    }
}
