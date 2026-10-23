package io.github.neotheon3.orangebox.core.obd

/** VIN read via mode 09 PID 02, validated per ISO 3779. */
object VinDecoder {
    private val allowed = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    private val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    sealed interface Result {
        /** [checkDigitValid] is only meaningful for North-American VINs; elsewhere position 9 is free. */
        data class Valid(val vin: String, val checkDigitValid: Boolean) : Result
        data class Invalid(val reason: String) : Result
    }

    /**
     * CAN: one (multi-frame) message `49 02 01 <17 ASCII>`.
     * ISO 9141 / KWP / J1850: five lines `49 02 <seq> <4 bytes>`; the first line carries 3 leading pad bytes.
     */
    fun decode(raw: String): Result {
        if (raw.contains("NO DATA", ignoreCase = true)) return Result.Invalid("Vehicle does not report a VIN")
        val msgs = ObdFrames.messages(raw) ?: return Result.Invalid("Incomplete or garbled response")
        val valid = msgs.filter { it.size >= 3 && it[0] == 0x49 && it[1] == 0x02 }
        if (valid.isEmpty()) return Result.Invalid("Unexpected response")
        val bytes = if (valid.size == 1) {
            valid[0].drop(3) // 49 02 <count>
        } else {
            valid.sortedBy { it[2] }.flatMap { it.drop(3) }
        }
        val text = bytes.filter { it != 0 }.map { it.toChar() }.joinToString("").trim()
        return validate(text)
    }

    fun validate(vin: String): Result {
        val v = vin.trim().uppercase()
        if (v.length != 17) return Result.Invalid("VIN must be 17 characters, got ${v.length}")
        if (!allowed.matches(v)) return Result.Invalid("VIN contains invalid characters (I, O, Q or non-alphanumeric)")
        return Result.Valid(v, checkDigit(v) == v[8])
    }

    fun checkDigit(vin: String): Char {
        val sum = vin.withIndex().sumOf { (i, c) -> transliterate(c) * weights[i] }
        val r = sum % 11
        return if (r == 10) 'X' else ('0' + r)
    }

    private fun transliterate(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        'A', 'J' -> 1; 'B', 'K', 'S' -> 2; 'C', 'L', 'T' -> 3; 'D', 'M', 'U' -> 4; 'E', 'N', 'V' -> 5
        'F', 'W' -> 6; 'G', 'P', 'X' -> 7; 'H', 'Y' -> 8; 'R', 'Z' -> 9
        else -> 0
    }
}
