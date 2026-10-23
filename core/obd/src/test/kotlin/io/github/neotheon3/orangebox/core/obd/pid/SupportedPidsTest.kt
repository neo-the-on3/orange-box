package io.github.neotheon3.orangebox.core.obd.pid

import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.FakeObdTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedPidsTest {
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16) }.toIntArray()

    @Test fun `J1979 example bitmap BE1FA813`() {
        // Wikipedia/J1979 example: 01,03,04,05,06,07,0C,0D,0E,0F,10,11,13,15,1C,1F,20
        val set = SupportedPids.decodeBitmap(0x00, bytes("BE1FA813"))
        assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20), set)
    }

    @Test fun `chain follows next-bitmap bit and stops`() = runTest {
        val t = FakeObdTransport(responder = {
            when (it) {
                "0100" -> "4100BE1FA813"
                "0120" -> "41208005B011" // 21, 2E, 2F(?) … and next bit
                "0140" -> "414040000000" // 42 only, no next
                "0160" -> error("must not be requested")
                else -> FakeObdTransport.defaultResponse(it)
            }
        })
        val s = Elm327Session(t).readSupportedPids()
        assertTrue(ObdPid.RPM in s)
        assertTrue(0x21 in s)
        assertTrue(ObdPid.CONTROL_MODULE_VOLTAGE in s)
        assertFalse(0x20 in s) // bitmap PIDs are not data PIDs
        assertFalse(ObdPid.FUEL_RATE in s)
        assertEquals(listOf("0100", "0120", "0140"), t.written)
    }

    @Test fun `filter keeps only supported`() {
        val s = SupportedPids(setOf(0x0C, 0x0D))
        assertEquals(listOf(ObdPid.RPM, ObdPid.SPEED), s.filter(listOf(ObdPid.RPM, ObdPid.MAF, ObdPid.SPEED)))
    }
}
