package io.github.neotheon3.orangebox.core.obd

import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.common.runCatchingCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the adapter reported during [Elm327Session.initialize]. */
data class Elm327Info(
    val adapterId: String,
    val protocol: ObdProtocol,
    val voltage: Double?,
)

/** ELM327 protocol numbers as reported by `ATDPN` (the leading `A` means "auto"). */
enum class ObdProtocol(val code: Char, val label: String, val isCan: Boolean) {
    AUTO('0', "Automatic", false),
    J1850_PWM('1', "SAE J1850 PWM", false),
    J1850_VPW('2', "SAE J1850 VPW", false),
    ISO_9141_2('3', "ISO 9141-2", false),
    KWP_5BAUD('4', "ISO 14230-4 KWP (5 baud)", false),
    KWP_FAST('5', "ISO 14230-4 KWP (fast)", false),
    CAN_11_500('6', "ISO 15765-4 CAN 11/500", true),
    CAN_29_500('7', "ISO 15765-4 CAN 29/500", true),
    CAN_11_250('8', "ISO 15765-4 CAN 11/250", true),
    CAN_29_250('9', "ISO 15765-4 CAN 29/250", true),
    J1939('A', "SAE J1939 CAN", true),
    UNKNOWN('?', "Unknown", false),
    ;

    companion object {
        fun fromDpn(raw: String?): ObdProtocol {
            val s = raw?.trim()?.uppercase().orEmpty()
            if (s.isEmpty()) return UNKNOWN
            val code = if (s.length == 2 && s[0] == 'A') s[1] else if (s.length == 1) s[0] else return UNKNOWN
            return entries.firstOrNull { it.code == code && it != UNKNOWN } ?: UNKNOWN
        }
    }
}

/**
 * ELM327 command layer on top of an [ObdTransport]: AT initialisation, probing and PID requests.
 *
 * Garbled or out-of-range frames are dropped (the request returns null) rather than propagated.
 * Link failures surface as [ObdError].
 */
class Elm327Session(
    private val transport: ObdTransport,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    /**
     * Polling speed-ups for real dongles (#193): `ATAT2` (aggressive adaptive timing) after the protocol is
     * known and, on CAN with a single answering ECU, the expected-response count (`010D1`) so the adapter
     * returns as soon as the reply arrives instead of waiting out its receive timeout. Falls back to plain
     * requests if the adapter rejects the count with `?`.
     */
    private val fastPolling: Boolean = false,
) {
    private val mutex = Mutex()

    var info: Elm327Info? = null
        private set

    /** ECUs that answered the `0100` probe (lines with a `41 00` header). */
    private var probeResponders = 0
    @Volatile private var countHintRejected = false

    /** Whether mode 01 requests currently carry the response-count hint. */
    val usesResponseCount: Boolean
        get() = fastPolling && !countHintRejected && info?.protocol?.isCan == true && probeResponders == 1

    /** Resets and configures the adapter, then probes the vehicle with `0100`. */
    suspend fun initialize(): Elm327Info {
        val id = command("ATZ").lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
        for (cmd in INIT_COMMANDS) command(cmd)
        val voltage = parseVoltage(command("ATRV"))
        probe()
        val protocol = detectProtocol()
        if (fastPolling) command("ATAT2")
        OrangeLog.i(
            OrangeLog.Areas.OBD, "protocol_detected", "protocol" to protocol.name, "can" to protocol.isCan, "adapter" to id,
            "ecus" to probeResponders, "response_count" to (fastPolling && protocol.isCan && probeResponders == 1),
        )
        return Elm327Info(id, protocol, voltage).also { info = it }
    }

    /**
     * After a successful search, `ATDPN` reports the protocol actually in use (e.g. `A6`). A garbled
     * or still-automatic (`A0`/`0`) reply is retried; if none of [PROTOCOL_ATTEMPTS] replies names a
     * concrete protocol the connection fails, so DTC parsing never runs with an unknown CAN/non-CAN format.
     */
    private suspend fun detectProtocol(): ObdProtocol {
        repeat(PROTOCOL_ATTEMPTS) { attempt ->
            val raw = command("ATDPN")
            val p = ObdProtocol.fromDpn(raw)
            if (p != ObdProtocol.UNKNOWN && p != ObdProtocol.AUTO) return p
            OrangeLog.w(OrangeLog.Areas.OBD, "protocol_reply_invalid", "attempt" to attempt + 1, "reply" to raw.take(16))
        }
        throw ObdError.Io("Could not determine the vehicle protocol (ATDPN unreadable)")
    }

    private suspend fun probe() {
        repeat(PROBE_ATTEMPTS) {
            val raw = command("0100")
            when {
                raw.contains("UNABLE TO CONNECT", ignoreCase = true) -> throw ObdError.UnsupportedProtocol()
                raw.contains("NO DATA", ignoreCase = true) -> throw ObdError.Io("Vehicle ECU did not answer (ignition off?)")
                raw.contains("BUS INIT", ignoreCase = true) && raw.contains("ERROR", ignoreCase = true) ->
                    throw ObdError.UnsupportedProtocol("Bus init failed")
            }
            if (parseResponse(raw, 0x01, 0x00, minBytes = 4) != null) {
                probeResponders = raw.lines().count { line -> hexBytes(line.trim())?.let { it.size >= 6 && it[0] == 0x41 && it[1] == 0x00 } == true }
                return
            }
        }
        throw ObdError.Io("Probe response unreadable")
    }

    /** Sends a raw command and returns the cleaned response text (echo, `SEARCHING...` stripped). */
    suspend fun command(cmd: String): String = mutex.withLock {
        transport.writeLine(cmd)
        val raw = transport.readUntilPrompt(timeoutMillis)
        clean(raw, cmd)
    }

    /** Battery voltage via `ATRV`, or null if unreadable. */
    suspend fun readVoltage(): Double? = parseVoltage(command("ATRV"))

    /** Requests a mode 01 PID and returns its data bytes, or null for NO DATA / `?` / garbled frames. */
    suspend fun requestPid(pid: Int, minBytes: Int = 1): IntArray? {
        val cmd = "01%02X".format(pid)
        if (usesResponseCount) {
            val raw = command(cmd + "1")
            if (raw.trim() != "?") return parseResponse(raw, 0x01, pid, minBytes)
            countHintRejected = true
            OrangeLog.w(OrangeLog.Areas.OBD, "response_count_rejected", "command" to cmd)
        }
        return parseResponse(command(cmd), 0x01, pid, minBytes)
    }

    /** DTCs of [kind] (mode 03/07/0A), e.g. `["P0133"]`. Null if the response was unreadable. */
    suspend fun readDtcs(kind: DtcKind = DtcKind.STORED): List<String>? =
        DtcDecoder.decode(command(kind.command), kind, isCan = info?.protocol?.isCan ?: true)

    /** Like [readDtcs], telling an unsupported mode apart from an unreadable reply (#196). */
    suspend fun readDtcResult(kind: DtcKind): DtcRead {
        val raw = command(kind.command)
        val result = DtcDecoder.read(raw, kind, isCan = info?.protocol?.isCan ?: true)
        if (result !is DtcRead.Codes) {
            OrangeLog.i(
                OrangeLog.Areas.OBD, "dtc_reply", "mode" to kind.command,
                "result" to if (result is DtcRead.Unsupported) "unsupported" else "unreadable",
                "reply" to raw.replace('\n', ' ').take(32),
            )
        }
        return result
    }

    /** VIN via mode 09 PID 02. */
    suspend fun readVin(): VinDecoder.Result = VinDecoder.decode(command("0902")).also { r ->
        when (r) {
            is VinDecoder.Result.Valid -> OrangeLog.i(OrangeLog.Areas.OBD, "vin_read", "vin" to OrangeLog.sensitive(r.vin), "check_digit_ok" to r.checkDigitValid)
            is VinDecoder.Result.Invalid -> OrangeLog.w(OrangeLog.Areas.OBD, "vin_invalid", "reason" to r.reason)
        }
    }

    /** Supported mode 01 PIDs, following the 00/20/40/… bitmap chain. */
    suspend fun readSupportedPids(): io.github.neotheon3.orangebox.core.obd.pid.SupportedPids {
        val bitmaps = linkedMapOf<Int, IntArray>()
        for (base in io.github.neotheon3.orangebox.core.obd.pid.SupportedPids.BITMAP_BASES) {
            val data = requestPid(base, minBytes = 4) ?: break
            bitmaps[base] = data
            if (data[3] and 0x01 == 0) break // next bitmap not supported
        }
        return io.github.neotheon3.orangebox.core.obd.pid.SupportedPids.fromBitmaps(bitmaps)
    }

    /** Reads and decodes one PID; null for unsupported/garbled answers. */
    suspend fun read(pid: io.github.neotheon3.orangebox.core.obd.pid.ObdPid): Double? =
        pid.decodeOrNull(requestPid(pid.pid, minBytes = pid.bytes))

    /** Clears DTCs and the MIL via mode 04. Returns true if the adapter acknowledged. */
    suspend fun clearDtcs(): Boolean = command("04").lines().any { hexBytes(it)?.firstOrNull() == 0x44 }

    /** MIL lamp state and stored DTC count from PID 01 01, or null if unreadable. */
    suspend fun readMonitorStatus(): MonitorStatus? = requestPid(0x01, minBytes = 4)?.let {
        MonitorStatus(milOn = it[0] and 0x80 != 0, dtcCount = it[0] and 0x7F)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        private const val PROBE_ATTEMPTS = 3
        private const val PROTOCOL_ATTEMPTS = 3
        val INIT_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")

        internal fun clean(raw: String, cmd: String): String = raw
            .replace('\r', '\n')
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) && !it.startsWith("SEARCHING", ignoreCase = true) }
            .joinToString("\n")

        fun parseVoltage(raw: String): Double? =
            Regex("""^\s*(\d{1,2}(?:\.\d{1,2})?)\s*V?\s*$""", RegexOption.IGNORE_CASE)
                .find(raw.lines().firstOrNull { it.isNotBlank() }.orEmpty())
                ?.groupValues?.get(1)?.toDoubleOrNull()
                ?.takeIf { it in 0.0..30.0 }

        /** Parses `41 0C 1A F8` style lines (spaces optional). Null unless the header matches and enough bytes follow. */
        fun parseResponse(raw: String, mode: Int, pid: Int, minBytes: Int): IntArray? {
            for (line in raw.lines()) {
                val bytes = hexBytes(line) ?: continue
                if (bytes.size < 2 + minBytes) continue
                if (bytes[0] != 0x40 + mode || bytes[1] != pid) continue
                return bytes.drop(2).toIntArray()
            }
            return null
        }

        /** Hex pairs of a line, or null if the line contains anything other than hex digits and spaces. */
        internal fun hexBytes(line: String): List<Int>? {
            val compact = line.replace(" ", "")
            if (compact.isEmpty() || compact.length % 2 != 0) return null
            if (!compact.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) return null
            return compact.chunked(2).map { it.toInt(16) }
        }

        fun decodeDtc(a: Int, b: Int): String {
            val system = "PCBU"[(a shr 6) and 0x03]
            val d1 = (a shr 4) and 0x03
            val rest = "%01X%02X".format(a and 0x0F, b)
            return "$system$d1$rest"
        }

        /** Inverse of [decodeDtc]; null for malformed codes. Used by the simulator and tests. */
        fun encodeDtc(code: String): Pair<Int, Int>? {
            val c = code.trim().uppercase()
            if (!Regex("^[PCBU][0-3][0-9A-F]{3}$").matches(c)) return null
            val sys = "PCBU".indexOf(c[0])
            val a = (sys shl 6) or (c[1].digitToInt() shl 4) or c[2].digitToInt(16)
            val b = c.substring(3).toInt(16)
            return a to b
        }

        /** Runs [block], mapping unexpected exceptions to [ObdError.Io]. */
        suspend fun <T> guarded(block: suspend () -> T): T =
            runCatchingCancellable { block() }.getOrElse { e -> throw (e as? ObdError ?: ObdError.Io(e.message ?: "I/O error", e)) }
    }
}

data class MonitorStatus(val milOn: Boolean, val dtcCount: Int)
