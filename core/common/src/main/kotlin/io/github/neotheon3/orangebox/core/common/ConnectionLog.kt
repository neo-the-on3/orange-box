package io.github.neotheon3.orangebox.core.common

import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of the OBD/ELM327 connection: transport open/close, every TX/RX line with its
 * timing, connection state changes, retries and errors. Debug builds enable it at startup so a misbehaving
 * real dongle can be diagnosed from an exported file; when disabled (release) [record] is a no-op.
 *
 * Capped at [maxChars] (about that many bytes for ASCII ELM traffic); the oldest lines are dropped first.
 * Recording never throws.
 */
object ConnectionLog {
    const val DEFAULT_MAX_CHARS = 4 * 1024 * 1024

    @Volatile var enabled: Boolean = false
    @Volatile var maxChars: Int = DEFAULT_MAX_CHARS
    /** Wall clock, replaceable in tests. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    private val lines = ArrayDeque<String>()
    private var chars = 0L
    private var dropped = 0L
    private val lock = Any()

    fun record(kind: String, text: String) {
        if (!enabled) return
        try {
            val line = "${timestamp(clock())} $kind $text"
            synchronized(lock) {
                lines.addLast(line)
                chars += line.length + 1
                while (chars > maxChars && lines.size > 1) {
                    chars -= lines.removeFirst().length + 1
                    dropped++
                }
            }
        } catch (_: Throwable) {
            // Diagnostics must never affect the connection.
        }
    }

    /** Escapes CR/LF so a multi-line ELM327 reply stays on one log line. */
    fun visible(raw: String): String = raw.replace("\r", "\\r").replace("\n", "\\n")

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun droppedLines(): Long = synchronized(lock) { dropped }

    fun writeTo(out: Writer) {
        val copy: List<String>
        val lost: Long
        synchronized(lock) { copy = lines.toList(); lost = dropped }
        if (lost > 0) out.write("# $lost older lines dropped (buffer cap ${maxChars / 1024} KiB)\n")
        copy.forEach { out.write(it); out.write("\n") }
        out.flush()
    }

    fun clear() = synchronized(lock) { lines.clear(); chars = 0; dropped = 0 }

    private fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(millis))
}
