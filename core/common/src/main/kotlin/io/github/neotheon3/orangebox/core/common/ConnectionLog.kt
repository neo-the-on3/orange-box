package io.github.neotheon3.orangebox.core.common

import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of the OBD/ELM327 connection: transport open/close, every request with its reply and
 * timing, connection state changes, retries and errors. Debug builds enable it at startup so a misbehaving
 * real dongle can be diagnosed from an exported file; when disabled (release) [record] is a no-op.
 *
 * Capped at [maxChars] of stored text; the oldest lines are dropped first. Lines are kept compact in memory
 * (epoch millis instead of an ISO timestamp, one `TR` line per request/reply pair, see `LoggingObdTransport`),
 * so the default cap holds about 3.5 h of 2 Hz polling (#200; RDF2's 4 MiB of the old format held 51 min).
 *
 * **Session header (#200).** Facts that explain the whole session (adapter version, protocol, supported-PID
 * bitmaps, poll plan, vehicle profile) are [pin]ned outside the ring buffer, so a long drive can't push them
 * out. The last [MAX_SESSIONS] sessions are kept and written first by [writeTo]. Never pin a VIN, a Bluetooth
 * address or a location.
 *
 * Recording never throws.
 */
object ConnectionLog {
    const val DEFAULT_MAX_CHARS = 8 * 1024 * 1024
    const val MAX_SESSIONS = 3

    @Volatile var enabled: Boolean = false
    @Volatile var maxChars: Int = DEFAULT_MAX_CHARS
    /** Wall clock, replaceable in tests. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    /** Stored as "<epoch millis> <kind> <text>"; formatted on the way out. */
    private val lines = ArrayDeque<String>()
    private var chars = 0L
    private var dropped = 0L
    private val lock = Any()

    private class Session(val number: Int, val startedAt: Long, val label: String) {
        val facts = LinkedHashMap<String, String>()
    }
    private val sessions = ArrayDeque<Session>()
    private var sessionCount = 0
    /** Facts that describe the app rather than one connection (the vehicle profile): copied into every new session. */
    private val sticky = LinkedHashMap<String, String>()

    fun record(kind: String, text: String) {
        if (!enabled) return
        try {
            val line = "${clock()} $kind $text"
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

    /** Starts a new session header (a transport open). [label] is the transport kind, never an address. */
    fun newSession(label: String) {
        if (!enabled) return
        synchronized(lock) {
            val s = Session(++sessionCount, clock(), label)
            s.facts.putAll(sticky)
            sessions.addLast(s)
            while (sessions.size > MAX_SESSIONS) sessions.removeFirst()
        }
    }

    /**
     * Sets [key] in the current session's header (replacing an earlier value). [sticky] facts also carry over
     * into later sessions until set again. Before the first session, non-sticky facts are ignored.
     */
    fun pin(key: String, value: String, sticky: Boolean = false) {
        if (!enabled) return
        try {
            synchronized(lock) {
                if (sticky) this.sticky[key] = value
                sessions.lastOrNull()?.facts?.put(key, value)
            }
        } catch (_: Throwable) {
        }
    }

    /** Escapes CR/LF so a multi-line ELM327 reply stays on one log line. */
    fun visible(raw: String): String = raw.replace("\r", "\\r").replace("\n", "\\n")

    /** [visible] without the leading/trailing line breaks and `>` prompt every ELM327 reply carries. */
    fun reply(raw: String): String = visible(raw.trim { it == '\r' || it == '\n' || it == ' ' || it == '>' })

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }.map(::format)

    fun droppedLines(): Long = synchronized(lock) { dropped }

    /** Session headers as written at the top of an export (also for tests). */
    fun headerLines(): List<String> = synchronized(lock) {
        sessions.flatMap { s ->
            listOf("# session ${s.number} started ${timestamp(s.startedAt)} (${s.label})") +
                s.facts.map { (k, v) -> "#   $k: $v" }
        }
    }

    fun writeTo(out: Writer) {
        val copy: List<String>
        val lost: Long
        val header: List<String>
        synchronized(lock) { copy = lines.toList(); lost = dropped; header = headerLines() }
        header.forEach { out.write(it); out.write("\n") }
        if (lost > 0) out.write("# $lost older lines dropped (buffer cap ${maxChars / 1024} KiB)\n")
        copy.forEach { out.write(format(it)); out.write("\n") }
        out.flush()
    }

    fun clear() = synchronized(lock) {
        lines.clear(); chars = 0; dropped = 0
        sessions.clear(); sessionCount = 0; sticky.clear()
    }

    private fun format(stored: String): String {
        val space = stored.indexOf(' ')
        val millis = stored.substring(0, space).toLongOrNull() ?: return stored
        return timestamp(millis) + stored.substring(space)
    }

    private fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(millis))
}
