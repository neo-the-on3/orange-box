package io.github.neotheon3.orangebox.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDecoderTest {
    @Test fun `negative response or question mark means the mode is unsupported (#196)`() {
        assertEquals(DtcRead.Unsupported, DtcDecoder.read("7F 0A 11", DtcKind.PERMANENT, isCan = true))
        assertEquals(DtcRead.Unsupported, DtcDecoder.read("7F0A12", DtcKind.PERMANENT, isCan = false))
        assertEquals(DtcRead.Unsupported, DtcDecoder.read("?", DtcKind.PERMANENT, isCan = true))
        assertEquals("NO DATA stays an empty list", DtcRead.Codes(emptyList()), DtcDecoder.read("NO DATA", DtcKind.PERMANENT, isCan = true))
        assertEquals(DtcRead.Codes(listOf("C0035")), DtcDecoder.read("4A01 4035", DtcKind.PERMANENT, isCan = true))
    }

    @Test fun `garbled or other negative responses stay unreadable (#196)`() {
        assertEquals(DtcRead.Unreadable, DtcDecoder.read("4#01", DtcKind.PERMANENT, isCan = true))
        assertEquals("other mode's NRC", DtcRead.Unreadable, DtcDecoder.read("7F 03 11", DtcKind.PERMANENT, isCan = true))
        assertEquals("busy, not unsupported", DtcRead.Unreadable, DtcDecoder.read("7F 0A 21", DtcKind.PERMANENT, isCan = true))
        assertEquals(DtcRead.Unreadable, DtcDecoder.read("", DtcKind.STORED, isCan = true))
        assertEquals("decode keeps returning null", null, DtcDecoder.decode("7F 0A 11", DtcKind.PERMANENT, isCan = true))
    }

    @Test fun `CAN single frame`() =
        assertEquals(listOf("P0133", "P0420"), DtcDecoder.decode("430201330420", DtcKind.STORED, isCan = true))

    @Test fun `CAN multi frame with spaces`() {
        val raw = "00A\n0: 43 04 01 33 04 20\n1: C1 23 91 11 00 00 00"
        assertEquals(listOf("P0133", "P0420", "U0123", "B1111"), DtcDecoder.decode(raw, DtcKind.STORED, isCan = true))
    }

    @Test fun `non-CAN multi line padded`() {
        val raw = "43 01 33 04 20 00 71\n43 00 00 00 00 00 00"
        assertEquals(listOf("P0133", "P0420", "P0071"), DtcDecoder.decode(raw, DtcKind.STORED, isCan = false))
    }

    @Test fun `zero codes`() {
        assertEquals(emptyList<String>(), DtcDecoder.decode("4300", DtcKind.STORED, isCan = true))
        assertEquals(emptyList<String>(), DtcDecoder.decode("43 00 00 00 00 00 00", DtcKind.STORED, isCan = false))
        assertEquals(emptyList<String>(), DtcDecoder.decode("NO DATA", DtcKind.PENDING, isCan = true))
    }

    @Test fun `pending and permanent headers`() {
        assertEquals(listOf("P0300"), DtcDecoder.decode("47010300", DtcKind.PENDING, isCan = true))
        assertEquals(listOf("C0035"), DtcDecoder.decode("4A01 4035", DtcKind.PERMANENT, isCan = true))
        assertNull("wrong header", DtcDecoder.decode("43010300", DtcKind.PENDING, isCan = true))
    }

    @Test fun `garbled or incomplete multi frame is dropped`() {
        assertNull(DtcDecoder.decode("00A\n0: 43 04 01 33 04 20", DtcKind.STORED, isCan = true))
        assertNull(DtcDecoder.decode("00A\n0: 43 #4 01 33 04 20\n1: C1 23 91 11 00 00 00", DtcKind.STORED, isCan = true))
    }
}

class VinDecoderTest {
    private val vin = "1M8GDM9AXKP042788" // ISO 3779 example with valid check digit

    @Test fun `CAN multi frame`() {
        val raw = "014\n0: 49 02 01 31 4D 38\n1: 47 44 4D 39 41 58 4B\n2: 50 30 34 32 37 38 38"
        assertEquals(VinDecoder.Result.Valid(vin, checkDigitValid = true), VinDecoder.decode(raw))
    }

    @Test fun `ISO 9141 five lines`() {
        val raw = listOf(
            "49 02 01 00 00 00 31", "49 02 02 4D 38 47 44", "49 02 03 4D 39 41 58",
            "49 02 04 4B 50 30 34", "49 02 05 32 37 38 38",
        ).joinToString("\n")
        assertEquals(VinDecoder.Result.Valid(vin, true), VinDecoder.decode(raw))
    }

    @Test fun `out of order ISO lines are sorted`() {
        val raw = "49 02 02 4D 38 47 44\n49 02 01 00 00 00 31\n49 02 03 4D 39 41 58\n49 02 05 32 37 38 38\n49 02 04 4B 50 30 34"
        assertTrue(VinDecoder.decode(raw) is VinDecoder.Result.Valid)
    }

    @Test fun `invalid VINs`() {
        assertTrue(VinDecoder.validate("1M8GDM9AXKP04278") is VinDecoder.Result.Invalid) // 16 chars
        assertTrue(VinDecoder.validate("1M8GDM9AXKP04278O") is VinDecoder.Result.Invalid) // letter O
        assertFalse((VinDecoder.validate("1M8GDM9A1KP042788") as VinDecoder.Result.Valid).checkDigitValid)
        assertTrue(VinDecoder.decode("NO DATA") is VinDecoder.Result.Invalid)
        assertTrue(VinDecoder.decode("014\n0: 49 02 01 31 4D 38") is VinDecoder.Result.Invalid)
        assertTrue(VinDecoder.decode("7F 09 12") is VinDecoder.Result.Invalid)
    }
}
