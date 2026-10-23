package io.github.neotheon3.orangebox.core.obd

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DongleLinkEventsTest {
    @Test fun `acl and simulator sources produce identical events`() = runTest {
        var selected: String? = "AA:BB:CC:DD:EE:FF"
        val bus = DongleLinkEvents { selected }
        bus.events.test {
            bus.report(DongleLinkEvent.Connected("aa:bb:cc:dd:ee:ff")) // ACL receiver (lower-case from intent)
            val acl = awaitItem()
            selected = DongleLinkEvent.SIMULATED_ADDRESS
            bus.report(DongleLinkEvent.Connected(DongleLinkEvent.SIMULATED_ADDRESS)) // Simulator button
            val sim = awaitItem()
            assertEquals(acl::class, sim::class)
            assertEquals(DongleLinkEvent.Connected("AA:BB:CC:DD:EE:FF"), acl)
            assertEquals(DongleLinkEvent.Connected(DongleLinkEvent.SIMULATED_ADDRESS), sim)
        }
    }

    @Test fun `events for other devices are ignored`() = runTest {
        val bus = DongleLinkEvents { "AA:BB:CC:DD:EE:FF" }
        bus.events.test {
            assertFalse(bus.report(DongleLinkEvent.Connected("11:22:33:44:55:66")))
            bus.report(DongleLinkEvent.Disconnected("AA:BB:CC:DD:EE:FF"))
            assertEquals(DongleLinkEvent.Disconnected("AA:BB:CC:DD:EE:FF"), awaitItem())
            expectNoEvents()
        }
    }

    @Test fun `nothing selected drops everything`() {
        assertFalse(DongleLinkEvents { null }.report(DongleLinkEvent.Connected("AA")))
    }
}
