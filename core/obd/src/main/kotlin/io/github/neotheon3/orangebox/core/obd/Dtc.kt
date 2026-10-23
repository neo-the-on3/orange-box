package io.github.neotheon3.orangebox.core.obd

/** Which DTC list to read. */
enum class DtcKind(val mode: Int) {
    STORED(0x03), PENDING(0x07), PERMANENT(0x0A);

    val responseHeader: Int get() = 0x40 + mode
    val command: String get() = "%02X".format(mode)
}

object DtcDecoder {
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
