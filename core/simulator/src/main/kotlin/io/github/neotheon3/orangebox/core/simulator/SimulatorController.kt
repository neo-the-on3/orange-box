package io.github.neotheon3.orangebox.core.simulator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Holds the live [SimulatorState]; the Simulator screen edits it and the simulated transport reads it. */
class SimulatorController(initial: SimulatorState = SimulatorState()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SimulatorState> = _state.asStateFlow()

    fun update(transform: (SimulatorState) -> SimulatorState) = _state.update(transform)

    fun set(state: SimulatorState) { _state.value = state }

    /**
     * Speed / RPM / throttle of a playing drive script, evaluated at the moment of the call. Set by
     * [DriveScriptPlayer] while it plays or coasts, null otherwise.
     */
    @Volatile var motion: (() -> Motion)? = null

    /** Car motion the simulated dongle reports. */
    data class Motion(val speedKph: Int, val rpm: Int, val throttlePercent: Int)

    /**
     * What the simulated dongle answers with *now*: [state], with the playing script's motion evaluated
     * at the time of the read. The [state] flow only gets the player's periodic refresh (for the UI);
     * reading it would answer with a value up to one (possibly overrunning) tick old while the app stamps
     * the sample with the read time, which skewed accelerations on slow devices (#142).
     */
    fun readState(): SimulatorState {
        val s = _state.value
        val m = motion?.invoke() ?: return s
        return s.copy(speedKph = m.speedKph, rpm = m.rpm, throttlePercent = m.throttlePercent)
    }

    fun reset() { _state.value = SimulatorState() }

    fun addPendingDtc(code: String): Boolean = addDtc(code, DtcList.PENDING)

    /** Adds [code] to [list]. Returns false (and changes nothing) if the code is not a valid DTC. */
    fun addDtc(code: String, list: DtcList): Boolean {
        val c = code.trim().uppercase()
        if (io.github.neotheon3.orangebox.core.obd.Elm327Session.encodeDtc(c) == null) return false
        update {
            when (list) {
                DtcList.STORED -> if (c in it.dtcs) it else it.copy(dtcs = it.dtcs + c, milOn = true)
                DtcList.PENDING -> if (c in it.pendingDtcs) it else it.copy(pendingDtcs = it.pendingDtcs + c)
                DtcList.PERMANENT -> if (c in it.permanentDtcs) it else it.copy(permanentDtcs = it.permanentDtcs + c)
            }
        }
        return true
    }

    fun setVin(vin: String) = update { it.copy(vin = vin.trim().uppercase().take(17)) }

    fun injectDtc(code: String): Boolean = addDtc(code, DtcList.STORED)

    fun clearDtcs() = update { it.copy(dtcs = emptyList(), pendingDtcs = emptyList(), milOn = false) }
}

enum class DtcList { STORED, PENDING, PERMANENT }
