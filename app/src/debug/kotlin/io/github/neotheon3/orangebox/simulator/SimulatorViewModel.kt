package io.github.neotheon3.orangebox.simulator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.neotheon3.orangebox.core.bluetooth.DefaultObdDongleProvider
import io.github.neotheon3.orangebox.core.bluetooth.ObdDongleProvider
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvent
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvents
import io.github.neotheon3.orangebox.core.obd.ObdConnectionManager
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.simulator.DriveScript
import io.github.neotheon3.orangebox.core.simulator.DriveScriptPlayer
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SimulatorUiState(
    val sim: SimulatorState = SimulatorState(),
    val connection: ObdConnectionState = ObdConnectionState.Idle,
    val lastLinkEvent: DongleLinkEvent? = null,
    val backendMode: BackendMode = BackendMode.DEFAULT,
    val simulatedSelected: Boolean = false,
)

/** All Simulator actions, so the stateless screen can be tested control by control. */
interface SimulatorActions {
    fun setUseSimulatedDongle(on: Boolean)
    fun update(transform: (SimulatorState) -> SimulatorState)
    fun linkConnected()
    fun linkDisconnected()
    fun injectDtc(code: String): Boolean
    fun clearDtcs()
    fun setBackendMode(mode: BackendMode)
    fun startScript(id: String)
    fun stopScript()
    fun reset()
}

@HiltViewModel
class SimulatorViewModel @Inject constructor(
    private val controller: SimulatorController,
    private val dongles: ObdDongleProvider,
    private val linkEvents: DongleLinkEvents,
    private val backend: BackendConfigRepository,
    manager: ObdConnectionManager,
    private val player: DriveScriptPlayer,
) : ViewModel(), SimulatorActions {
    val uiState: StateFlow<SimulatorUiState> = combine(
        controller.state, manager.state, manager.lastLinkEvent, backend.config, dongles.selected,
    ) { sim, conn, link, cfg, selected ->
        SimulatorUiState(sim, conn, link, cfg.mode, selected?.address == DongleLinkEvent.SIMULATED_ADDRESS)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SimulatorUiState(sim = controller.state.value))

    override fun setUseSimulatedDongle(on: Boolean) {
        controller.update { it.copy(useSimulatedDongle = on) }
        viewModelScope.launch {
            if (on) dongles.select(DefaultObdDongleProvider.SIMULATED)
            else if (uiState.value.simulatedSelected) dongles.select(null)
        }
    }

    override fun update(transform: (SimulatorState) -> SimulatorState) = controller.update(transform)
    override fun linkConnected() { linkEvents.report(DongleLinkEvent.Connected(DongleLinkEvent.SIMULATED_ADDRESS)) }
    override fun linkDisconnected() { linkEvents.report(DongleLinkEvent.Disconnected(DongleLinkEvent.SIMULATED_ADDRESS)) }
    override fun injectDtc(code: String) = controller.injectDtc(code)
    override fun clearDtcs() = controller.clearDtcs()
    override fun setBackendMode(mode: BackendMode) { viewModelScope.launch { backend.setMode(mode) } }

    override fun startScript(id: String) { DriveScript.byId(id)?.let(player::start) }
    override fun stopScript() = player.stop()

    override fun reset() {
        player.stop()
        val wasSimulated = uiState.value.simulatedSelected
        controller.reset()
        if (wasSimulated) viewModelScope.launch { dongles.select(null) }
    }
}
