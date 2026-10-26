package io.github.neotheon3.orangebox.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UtcInstantTest {
    private val pattern = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z$") // spec UtcInstant

    @Test fun `parses 0 to 9 fractional digits and offsets as the same instant`() {
        val base = Instant.parse("2026-10-23T14:05:00Z")
        assertEquals(base, UtcInstantSerializer.parse("2026-10-23T14:05:00Z"))
        assertEquals(base.plusMillis(100), UtcInstantSerializer.parse("2026-10-23T14:05:00.1Z"))
        assertEquals(base.plusMillis(120), UtcInstantSerializer.parse("2026-10-23T14:05:00.12Z"))
        assertEquals(base.plusMillis(123), UtcInstantSerializer.parse("2026-10-23T14:05:00.123Z"))
        assertEquals(base.plusNanos(123_456_789), UtcInstantSerializer.parse("2026-10-23T14:05:00.123456789Z"))
        assertEquals(base, UtcInstantSerializer.parse("2026-10-23T19:35:00+05:30"))
    }

    @Test fun `ordering uses instants, not strings`() {
        // As strings ".1Z" sorts after ".099Z"; as instants it's later too, but ".1Z" vs ".100Z" are equal.
        assertEquals(UtcInstantSerializer.parse("2026-10-23T14:05:00.1Z"), UtcInstantSerializer.parse("2026-10-23T14:05:00.100Z"))
        assertTrue(UtcInstantSerializer.parse("2026-10-23T14:05:00Z") < UtcInstantSerializer.parse("2026-10-23T14:05:00.001Z"))
    }

    @Test fun `writes UTC with Z, matching the spec pattern`() {
        listOf(
            Instant.parse("2026-10-23T14:05:00Z"),
            Instant.parse("2026-10-23T14:05:00.123Z"),
            Instant.ofEpochSecond(1_761_228_300, 123_456_789),
        ).forEach { assertTrue(UtcInstantSerializer.format(it), pattern.matches(UtcInstantSerializer.format(it))) }
    }
}
