package io.github.neotheon3.orangebox.core.obd

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Elm327SessionTest {
    @Test fun `init sends AT sequence then probes`() = runTest {
        val t = FakeObdTransport()
        val info = Elm327Session(t).initialize()
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATRV", "0100", "ATDPN"), t.written)
        assertEquals("ELM327 v1.5", info.adapterId)
        assertEquals(ObdProtocol.CAN_11_500, info.protocol)
        assertEquals(12.6, info.voltage!!, 0.001)
    }

    @Test fun `fast polling on single-ECU CAN - ATAT2 and the response-count hint (#193)`() = runTest {
        val t = FakeObdTransport(responder = { if (it == "010C1") "410C1AF8" else FakeObdTransport.defaultResponse(it) })
        val s = Elm327Session(t, fastPolling = true)
        s.initialize()
        assertTrue(t.written.contains("ATAT2"))
        assertTrue(s.usesResponseCount)
        assertEquals(1726.0, s.read(io.github.neotheon3.orangebox.core.obd.pid.ObdPid.RPM)!!, 0.0)
        assertEquals("010C1", t.written.last())
    }

    @Test fun `response-count hint falls back when the adapter answers with a question mark (#193)`() = runTest {
        val t = FakeObdTransport(responder = { if (it == "010C1") "?" else FakeObdTransport.defaultResponse(it) })
        val s = Elm327Session(t, fastPolling = true)
        s.initialize()
        assertEquals(1726.0, s.read(io.github.neotheon3.orangebox.core.obd.pid.ObdPid.RPM)!!, 0.0)
        assertTrue(!s.usesResponseCount)
        s.read(io.github.neotheon3.orangebox.core.obd.pid.ObdPid.RPM)
        assertEquals(listOf("010C1", "010C", "010C"), t.written.filter { it.startsWith("010C") })
    }

    @Test fun `no response-count hint with two ECUs, on non-CAN, or without fast polling (#193)`() = runTest {
        val twoEcus = FakeObdTransport(responder = { if (it == "0100") "4100BE3EB811\r4100983B0011" else FakeObdTransport.defaultResponse(it) })
        assertTrue(!Elm327Session(twoEcus, fastPolling = true).apply { initialize() }.usesResponseCount)
        val kwp = FakeObdTransport(responder = { if (it == "ATDPN") "A5" else FakeObdTransport.defaultResponse(it) })
        assertTrue(!Elm327Session(kwp, fastPolling = true).apply { initialize() }.usesResponseCount)
        val plain = FakeObdTransport()
        assertTrue(!Elm327Session(plain).apply { initialize() }.usesResponseCount)
        assertTrue(!plain.written.contains("ATAT2"))
    }

    @Test fun `garbled or auto ATDPN after the search is retried`() = runTest {
        val replies = ArrayDeque(listOf("A#\u0007", "A0", "A6"))
        val t = FakeObdTransport(responder = { if (it == "ATDPN") replies.removeFirst() else FakeObdTransport.defaultResponse(it) })
        val info = Elm327Session(t).initialize()
        assertEquals(ObdProtocol.CAN_11_500, info.protocol)
        assertTrue(info.protocol.isCan)
        assertEquals(3, t.written.count { it == "ATDPN" })
    }

    @Test fun `protocol that cannot be determined fails clearly instead of staying Automatic`() = runTest {
        val t = FakeObdTransport(responder = { if (it == "ATDPN") "A0" else FakeObdTransport.defaultResponse(it) })
        try {
            Elm327Session(t).initialize()
            fail("expected ObdError.Io")
        } catch (e: ObdError.Io) {
            assertTrue(e.message!!.contains("protocol"))
            assertTrue(!e.isTerminal) // the connection manager retries it
        }
    }

    @Test fun `ISO 9141 is detected as non-CAN`() = runTest {
        val t = FakeObdTransport(responder = { if (it == "ATDPN") "A3" else FakeObdTransport.defaultResponse(it) })
        val s = Elm327Session(t)
        assertEquals(ObdProtocol.ISO_9141_2, s.initialize().protocol)
        t.responder = { if (it == "03") "43 01 33 04 20 00 00" else FakeObdTransport.defaultResponse(it) }
        assertEquals(listOf("P0133", "P0420"), s.readDtcs())
    }

    @Test fun `prompt parsing strips echo and searching`() {
        assertEquals("410C1AF8", Elm327Session.clean("010C\r410C1AF8\r\r", "010C"))
        assertEquals("4100BE3EB811", Elm327Session.clean("SEARCHING...\r4100BE3EB811", "0100"))
    }

    @Test fun `pid with spaces and without`() {
        assertEquals(listOf(0x1A, 0xF8), Elm327Session.parseResponse("41 0C 1A F8", 1, 0x0C, 2)!!.toList())
        assertEquals(listOf(0x1A, 0xF8), Elm327Session.parseResponse("410C1AF8", 1, 0x0C, 2)!!.toList())
    }

    @Test fun `garbled and out-of-range frames are dropped`() = runTest {
        val responses = ArrayDeque(listOf("41#C1AF8", "410D12", "410C1A", "NO DATA", "?", "410C1AF8"))
        val s = Elm327Session(FakeObdTransport(responder = { responses.removeFirst() }))
        repeat(5) { assertNull(s.requestPid(0x0C, minBytes = 2)) } // noise, wrong PID, too short, NO DATA, ?
        assertEquals(listOf(0x1A, 0xF8), s.requestPid(0x0C, minBytes = 2)!!.toList())
    }

    @Test fun `timeout surfaces as Timeout after the configured time`() = runTest {
        val s = Elm327Session(FakeObdTransport(responder = { null }), timeoutMillis = 3_000)
        try {
            s.command("ATZ"); fail("expected timeout")
        } catch (e: ObdError.Timeout) {
            assertEquals(3_000, currentTime)
        }
    }

    @Test fun `UNABLE TO CONNECT maps to UnsupportedProtocol`() = runTest {
        val t = FakeObdTransport(responder = { if (it == "0100") "SEARCHING...\rUNABLE TO CONNECT" else FakeObdTransport.defaultResponse(it) })
        try {
            Elm327Session(t).initialize(); fail("expected UnsupportedProtocol")
        } catch (e: ObdError.UnsupportedProtocol) {
            assertTrue(e.isTerminal)
        }
    }

    @Test fun `NO DATA on probe is a retryable Io error`() = runTest {
        val t = FakeObdTransport(responder = { if (it == "0100") "NO DATA" else FakeObdTransport.defaultResponse(it) })
        try {
            Elm327Session(t).initialize(); fail()
        } catch (e: ObdError.Io) {
            assertTrue(!e.isTerminal)
        }
    }

    @Test fun `garbled probe is retried`() = runTest {
        var probes = 0
        val t = FakeObdTransport(responder = {
            if (it == "0100") (if (++probes < 3) "41~0BE3E" else "4100BE3EB811") else FakeObdTransport.defaultResponse(it)
        })
        Elm327Session(t).initialize()
        assertEquals(3, probes)
    }

    @Test fun `voltage parsing`() {
        assertEquals(12.4, Elm327Session.parseVoltage("12.4V")!!, 0.0)
        assertEquals(14.1, Elm327Session.parseVoltage("14.1")!!, 0.0)
        assertNull(Elm327Session.parseVoltage("1#.4V"))
        assertNull(Elm327Session.parseVoltage("?"))
        assertNull("adapter glitch seen on the SX4", Elm327Session.parseVoltage("0.7\r\r"))
    }

    @Test fun `protocol from ATDPN`() {
        assertEquals(ObdProtocol.CAN_11_500, ObdProtocol.fromDpn("A6"))
        assertEquals(ObdProtocol.ISO_9141_2, ObdProtocol.fromDpn("3"))
        assertEquals(ObdProtocol.UNKNOWN, ObdProtocol.fromDpn("Z#"))
    }

    @Test fun `dtc encode decode round trip`() {
        for (code in listOf("P0133", "C1234", "B0001", "U3FFF")) {
            val (a, b) = Elm327Session.encodeDtc(code)!!
            assertEquals(code, Elm327Session.decodeDtc(a, b))
        }
        assertNull(Elm327Session.encodeDtc("X0133"))
    }

    @Test fun `mode 03 CAN frame decodes codes`() = runTest {
        val t = FakeObdTransport(responder = { if (it == "03") "430201330420" else FakeObdTransport.defaultResponse(it) })
        val s = Elm327Session(t)
        s.initialize()
        assertEquals(listOf("P0133", "P0420"), s.readDtcs())
    }
}
