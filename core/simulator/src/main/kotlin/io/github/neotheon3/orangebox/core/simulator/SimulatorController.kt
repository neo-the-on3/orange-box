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

    fun reset() { _state.value = SimulatorState() }

    fun injectDtc(code: String): Boolean {
        val c = code.trim().uppercase()
        if (io.github.neotheon3.orangebox.core.obd.Elm327Session.encodeDtc(c) == null) return false
        update { if (c in it.dtcs) it else it.copy(dtcs = it.dtcs + c, milOn = true) }
        return true
    }

    fun clearDtcs() = update { it.copy(dtcs = emptyList(), milOn = false) }
}
