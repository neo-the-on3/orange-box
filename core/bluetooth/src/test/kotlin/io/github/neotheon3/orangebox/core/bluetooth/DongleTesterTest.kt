package io.github.neotheon3.orangebox.core.bluetooth

import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdProtocol
import io.github.neotheon3.orangebox.core.simulator.SimulatedElm327Transport
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import io.github.neotheon3.orangebox.core.testing.FakeObdConnection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DongleTesterTest {
    private fun sim(state: SimulatorState = SimulatorState()) = SimulatorController(state)

    @Test fun `simulated dongle passes`() = runTest {
        val c = sim()
        val r = DefaultDongleTester.test({ SimulatedElm327Transport(c) }, FakeObdConnection())
        assertTrue(r.toString(), r is DongleTestResult.Success && !r.alreadyConnected)
    }

    @Test fun `already connected reports the live session without opening a transport`() = runTest {
        val info = Elm327Info("ELM327 v1.5", ObdProtocol.entries.first(), 12.6)
        var opened = 0
        val r = DefaultDongleTester.test({ opened++; error("must not open") }, FakeObdConnection(ObdConnectionState.Connected(info)))
        assertEquals(DongleTestResult.Success(info, alreadyConnected = true), r)
        assertEquals(0, opened)
    }

    @Test fun `failures are reported, not thrown`() = runTest {
        val notPaired = DefaultDongleTester.test({ throw ObdError.NotPaired() }, null)
        assertTrue((notPaired as DongleTestResult.Failure).error is ObdError.NotPaired)

        val c = sim(SimulatorState(connectionFailure = true))
        val io = DefaultDongleTester.test({ SimulatedElm327Transport(c) }, null)
        assertTrue((io as DongleTestResult.Failure).error is ObdError.Io)

        val garbled = DefaultDongleTester.test({ SimulatedElm327Transport(sim(SimulatorState(garbledProtocolReplies = true))) }, null)
        assertTrue((garbled as DongleTestResult.Failure).error.message!!.contains("protocol"))
    }
}
