package io.github.neotheon3.orangebox.core.obd

/** Which DTC list to read. */
enum class DtcKind(val mode: Int) {
    STORED(0x03), PENDING(0x07), PERMANENT(0x0A);

    val responseHeader: Int get() = 0x40 + mode
    val command: String get() = "%02X".format(mode)
}

/** Outcome of one DTC list read (#196). */
sealed interface DtcRead {
    /** The list as reported (empty for none / `NO DATA`). */
    data class Codes(val codes: List<String>) : DtcRead
    /** The ECU doesn't implement this mode: a `7F <mode> 11/12` negative response or the adapter's `?`. */
    data object Unsupported : DtcRead
    /** Garbled, truncated or otherwise unexpected reply. */
    data object Unreadable : DtcRead
}

object DtcDecoder {
    /** Negative-response codes that mean "this service isn't implemented" (ISO 14229/14230). */
    private val NOT_SUPPORTED_NRC = setOf(0x11, 0x12)

    /**
     * Like [decode], but tells a mode the car doesn't support ([DtcRead.Unsupported], e.g. mode 0A on many
     * pre-2010 / BS-IV ECUs) apart from a reply that couldn't be read ([DtcRead.Unreadable]).
     */
    fun read(raw: String, kind: DtcKind, isCan: Boolean): DtcRead {
        decode(raw, kind, isCan)?.let { return DtcRead.Codes(it) }
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty() && lines.all { it == "?" }) return DtcRead.Unsupported
        val negative = lines.mapNotNull { Elm327Session.hexBytes(it) }
            .any { it.size >= 3 && it[0] == 0x7F && it[1] == kind.mode && it[2] in NOT_SUPPORTED_NRC }
        return if (negative) DtcRead.Unsupported else DtcRead.Unreadable
    }

    /**
     * Decodes mode 03/07/0A responses.
     * CAN: one message `[4x, count, A1, B1, A2, B2 …]` (possibly multi-frame).
     * Non-CAN: one or more lines `[4x, A1, B1, A2, B2, A3, B3]`, padded with `00 00`.
     * Returns null when no message carries the expected header (garbled / wrong response).
     */
    fun decode(raw: String, kind: DtcKind, isCan: Boolean): List<String>? {
        if (raw.contains("NO DATA", ignoreCase = true)) return emptyList()
        val msgs = ObdFrames.messages(raw) ?: return null
        val valid = msgs.filter { it.isNotEmpty() && it[0] == kind.responseHeader }
        if (valid.isEmpty()) return null
        val codes = LinkedHashSet<String>()
        for (msg in valid) {
            var data = msg.drop(1)
            if (isCan) {
                val count = data.firstOrNull() ?: 0
                data = data.drop(1).take(count * 2)
            }
            data.chunked(2).filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }
                .forEach { (a, b) -> codes += Elm327Session.decodeDtc(a, b) }
        }
        return codes.toList()
    }
}
