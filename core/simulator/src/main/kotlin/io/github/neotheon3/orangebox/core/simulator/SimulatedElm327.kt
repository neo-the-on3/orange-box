package io.github.neotheon3.orangebox.core.simulator

import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdTransport
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * In-process ELM327 that answers like a v1.5 clone on ISO 15765-4 CAN 11/500 with spaces and
 * headers off (after `ATS0`/`ATH0`). Pure logic: [respond] maps a command to the response text.
 */
class SimulatedElm327(private val controller: SimulatorController) {
    private var searched = false

    fun respond(command: String): String {
        val s = controller.state.value
        val cmd = command.trim().uppercase().replace(" ", "")
        return when {
            cmd == "ATZ" -> { searched = false; "ELM327 v1.5" }
            cmd == "ATI" -> "ELM327 v1.5"
            cmd == "ATRV" -> "%.1fV".format(java.util.Locale.US, voltage(s))
            cmd == "ATDPN" && s.garbledProtocolReplies -> "A#"
            cmd == "ATDPN" -> if (searched && !s.unsupportedProtocol) "A6" else "A0"
            cmd.startsWith("AT") -> "OK"
            s.unsupportedProtocol -> "SEARCHING...\nUNABLE TO CONNECT"
            cmd == "03" -> dtcResponse(0x43, s.dtcs)
            cmd == "07" -> dtcResponse(0x47, s.pendingDtcs)
            cmd == "0A" -> dtcResponse(0x4A, s.permanentDtcs)
            cmd == "04" -> { controller.clearDtcs(); "44" }
            cmd == "0902" -> if (s.vin.isEmpty()) "NO DATA" else vinResponse(s.vin)
            cmd.length == 4 && cmd.startsWith("01") -> mode01(cmd.substring(2), s)
            else -> "?"
        }
    }

    private fun mode01(pidHex: String, s: SimulatorState): String {
        val pid = pidHex.toIntOrNull(16) ?: return "?"
        val firstSearch = !searched
        searched = true
        val data = when {
            pid in BITMAP_BASES -> bitmap(pid)
            pid !in SUPPORTED -> return "NO DATA"
            else -> pidData(pid, s)
        }
        val line = "41%02X%s".format(pid, data)
        return if (firstSearch) "SEARCHING...\n$line" else line
    }

    private fun pidData(pid: Int, s: SimulatorState): String {
        val m = EngineModel(s)
        return when (pid) {
            0x01 -> "%02X076500".format((if (s.milOn) 0x80 else 0) or s.dtcs.size.coerceAtMost(0x7F))
            0x03 -> "%02X00".format(m.fuelSystemStatus)
            0x04 -> "%02X".format(pctByte(m.loadPercent))
            0x05 -> "%02X".format((s.coolantC + 40).coerceIn(0, 255))
            0x0B -> "%02X".format(m.mapKpa.toInt().coerceIn(0, 255))
            0x0C -> "%04X".format((s.effectiveRpm * 4).coerceIn(0, 0xFFFF))
            0x0D -> "%02X".format(if (s.engineOn) s.speedKph.coerceIn(0, 255) else 0)
            0x0F -> "%02X".format(INTAKE_C + 40)
            0x10 -> "%04X".format((m.mafGps * 100).toInt().coerceIn(0, 0xFFFF))
            0x11 -> "%02X".format(pctByte(s.throttlePercent.toDouble()))
            0x2F -> "%02X".format(pctByte(s.fuelPercent.toDouble()))
            0x33 -> "%02X".format(BARO_KPA)
            0x42 -> "%04X".format((voltage(s) * 1000).toInt())
            0x46 -> "%02X".format(AMBIENT_C + 40)
            0x51 -> "01" // gasoline
            0x5C -> "%02X".format((s.coolantC + 5 + 40).coerceIn(0, 255))
            else -> return "NO DATA"
        }
    }

    private fun voltage(s: SimulatorState) = if (s.engineOn) 14.1 else 12.4

    private fun bitmap(base: Int): String {
        var bits = 0L
        for (pid in SUPPORTED) if (pid in base + 1..base + 32) bits = bits or (1L shl (32 - (pid - base)))
        if (BITMAP_BASES.any { it == base + 0x20 } && SUPPORTED.any { it > base + 0x20 }) bits = bits or 1L
        return "%08X".format(bits)
    }

    /** CAN: single frame up to 7 bytes, otherwise ISO-TP multi-frame as printed by the ELM327 (CAF on, headers off). */
    private fun dtcResponse(header: Int, codes: List<String>): String {
        val payload = mutableListOf(header, codes.size)
        codes.mapNotNull { Elm327Session.encodeDtc(it) }.forEach { (a, b) -> payload += a; payload += b }
        return canMessage(payload)
    }

    private fun vinResponse(vin: String): String = canMessage(listOf(0x49, 0x02, 0x01) + vin.take(17).map { it.code and 0x7F })

    companion object {
        val BITMAP_BASES = setOf(0x00, 0x20, 0x40)
        val SUPPORTED = sortedSetOf(0x01, 0x03, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x2F, 0x33, 0x42, 0x46, 0x51, 0x5C)
        const val INTAKE_C = 30
        const val AMBIENT_C = 22
        const val BARO_KPA = 101

        private fun pctByte(p: Double) = (p * 255 / 100).toInt().coerceIn(0, 255)

        fun canMessage(payload: List<Int>): String {
            fun hex(bytes: List<Int>) = bytes.joinToString("") { "%02X".format(it) }
            if (payload.size <= 7) return hex(payload)
            val sb = StringBuilder("%03X".format(payload.size))
            sb.append("\n0:").append(hex(payload.take(6)))
            payload.drop(6).chunked(7).forEachIndexed { i, chunk ->
                sb.append("\n").append("%X".format((i + 1) % 16)).append(":").append(hex(chunk + List(7 - chunk.size) { 0 }))
            }
            return sb.toString()
        }
    }
}

/**
 * Toy engine model so derived metrics behave plausibly: a 1.6 L petrol engine.
 * MAF ≈ RPM/120 · 1.6 L · 1.18 g/L · VE, with VE rising from 0.2 (closed throttle) to 1.0 (wide open).
 * Closed throttle above 20 km/h and 1100 rpm reports decel fuel cut (PID 03 = 0x04).
 */
internal class EngineModel(s: SimulatorState) {
    private val rpm = s.effectiveRpm.toDouble()
    val fuelCut = s.engineOn && s.throttlePercent == 0 && s.speedKph >= 20 && rpm >= 1100
    val fuelSystemStatus = when {
        !s.engineOn -> 0x00
        fuelCut -> 0x04
        else -> 0x02
    }
    private val ve = if (fuelCut) 0.12 else 0.2 + 0.8 * s.throttlePercent / 100.0
    val mafGps = if (!s.engineOn) 0.0 else rpm / 120.0 * 1.6 * 1.18 * ve
    val loadPercent = if (!s.engineOn) 0.0 else if (fuelCut) 2.0 else (15.0 + 0.85 * s.throttlePercent).coerceAtMost(100.0)
    val mapKpa = if (!s.engineOn) SimulatedElm327.BARO_KPA.toDouble() else 28.0 + 0.73 * s.throttlePercent
}

/**
 * [ObdTransport] backed by [SimulatedElm327]. Honours the failure toggles:
 * unpaired → [ObdError.NotPaired] on open; connection failure → [ObdError.Io] on open and on
 * every write; slow → 1.5 s per response; garbled → ~30% of responses corrupted with non-hex noise.
 */
class SimulatedElm327Transport(
    private val controller: SimulatorController,
    private val random: Random = Random.Default,
) : ObdTransport {
    private val elm = SimulatedElm327(controller)
    private var open = false
    private var pending: String? = null

    override suspend fun open() {
        val s = controller.state.value
        if (!s.paired) throw ObdError.NotPaired("Simulated dongle is not paired")
        if (s.connectionFailure) throw ObdError.Io("Simulated connection failure")
        open = true
    }

    override suspend fun writeLine(line: String) {
        if (!open) throw ObdError.Io("Transport not open")
        if (controller.state.value.connectionFailure) {
            open = false
            throw ObdError.Io("Simulated connection failure")
        }
        pending = elm.respond(line)
    }

    override suspend fun readUntilPrompt(timeoutMillis: Long): String {
        val s = controller.state.value
        val response = pending ?: throw ObdError.Timeout()
        pending = null
        if (s.slowResponses) {
            if (SLOW_DELAY_MS > timeoutMillis) {
                delay(timeoutMillis)
                throw ObdError.Timeout()
            }
            delay(SLOW_DELAY_MS)
        }
        return if (s.garbledResponses && random.nextDouble() < GARBLE_PROBABILITY) garble(response) else response
    }

    override suspend fun close() {
        open = false
        pending = null
    }

    private fun garble(text: String): String {
        if (text.isEmpty()) return "#"
        val chars = text.toCharArray()
        repeat(1 + random.nextInt(3)) { chars[random.nextInt(chars.size)] = NOISE[random.nextInt(NOISE.length)] }
        return String(chars)
    }

    companion object {
        const val SLOW_DELAY_MS = 1_500L
        const val GARBLE_PROBABILITY = 0.3
        private const val NOISE = "#%~&*"
    }
}
