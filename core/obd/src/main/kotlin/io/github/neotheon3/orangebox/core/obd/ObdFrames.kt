package io.github.neotheon3.orangebox.core.obd

/**
 * Reassembles ELM327 responses (headers off) into messages.
 *
 * - CAN multi-frame (ISO 15765-4 with CAN auto-formatting): a first line holding the byte count in
 *   hex (e.g. `014`), then lines prefixed `0:`, `1:` … whose payloads are concatenated in index order
 *   and truncated to the byte count. Becomes one message.
 * - Anything else (single-frame CAN, J1850, ISO 9141, KWP): every hex line is its own message.
 *
 * Lines that are not hex (noise, `SEARCHING...`) are ignored. Returns null if a multi-frame
 * sequence is incomplete or garbled, so callers can drop it.
 */
object ObdFrames {
    private val indexed = Regex("""^([0-9A-Fa-f]):\s*(.*)$""")
    private val countLine = Regex("""^[0-9A-Fa-f]{3}$""")

    fun messages(raw: String): List<List<Int>>? {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val isMulti = lines.any { indexed.matches(it) }
        if (!isMulti) return lines.mapNotNull { Elm327Session.hexBytes(it) }
        var expected: Int? = null
        val parts = sortedMapOf<Int, List<Int>>()
        for (line in lines) {
            val m = indexed.matchEntire(line)
            when {
                m != null -> parts[m.groupValues[1].toInt(16)] = Elm327Session.hexBytes(m.groupValues[2]) ?: return null
                countLine.matches(line.replace(" ", "")) -> expected = line.replace(" ", "").toInt(16)
                else -> Elm327Session.hexBytes(line) ?: continue
            }
        }
        // Frame indexes wrap 0..F; keep arrival order for long messages (VIN fits in 3 frames anyway).
        if (parts.keys.toList() != (0 until parts.size).toList()) return null
        val bytes = parts.values.flatten()
        val count = expected ?: return listOf(bytes)
        if (bytes.size < count) return null
        return listOf(bytes.take(count))
    }
}
