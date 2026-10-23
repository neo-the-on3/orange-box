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
            cmd == "ATRV" -> if (s.engineOn) "14.1V" else "12.4V"
            cmd == "ATDPN" -> if (searched && !s.unsupportedProtocol) "A6" else "A0"
            cmd.startsWith("AT") -> "OK"
            s.unsupportedProtocol -> "SEARCHING...\nUNABLE TO CONNECT"
            cmd == "03" -> mode03(s)
            cmd == "04" -> { controller.clearDtcs(); "44" }
            cmd.length == 4 && cmd.startsWith("01") -> mode01(cmd.substring(2), s)
            else -> "?"
        }
    }

    private fun mode01(pidHex: String, s: SimulatorState): String {
        val pid = pidHex.toIntOrNull(16) ?: return "?"
        val firstSearch = !searched
        searched = true
        val data = when (pid) {
            0x00 -> "BE3EB811" // 01-1F supported incl. 01, 04, 05, 0C, 0D, 2F-range flag
            0x01 -> "%02X076500".format((if (s.milOn) 0x80 else 0) or s.dtcs.size.coerceAtMost(0x7F))
            0x04 -> "%02X".format(if (s.engineOn) 0x33 else 0)
            0x05 -> "%02X".format((s.coolantC + 40).coerceIn(0, 255))
            0x0C -> "%04X".format((s.effectiveRpm * 4).coerceIn(0, 0xFFFF))
            0x0D -> "%02X".format(if (s.engineOn) s.speedKph.coerceIn(0, 255) else 0)
            0x20 -> "80000001"
            0x2F -> "%02X".format((s.fuelPercent * 255 + 50) / 100)
            else -> return "NO DATA"
        }
        val line = "41%02X%s".format(pid, data)
        return if (firstSearch) "SEARCHING...\n$line" else line
    }

    private fun mode03(s: SimulatorState): String {
        if (s.dtcs.isEmpty()) return "4300"
        val sb = StringBuilder("43%02X".format(s.dtcs.size))
        s.dtcs.mapNotNull { Elm327Session.encodeDtc(it) }.forEach { (a, b) -> sb.append("%02X%02X".format(a, b)) }
        return sb.toString()
    }
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
