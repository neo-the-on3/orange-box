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
import io.github.neotheon3.orangebox.core.obd.DtcKind
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.VinDecoder
import io.github.neotheon3.orangebox.core.simulator.DtcList
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** What the app itself computed (TelemetryEngine), for the debug readout. */
    val telemetry: TelemetrySnapshot? = null,
    val readout: DiagnosticsReadout? = null,
)

/** DTCs and VIN as the app read them from the (simulated or real) car through the live session. */
data class DiagnosticsReadout(
    val stored: List<String>? = null,
    val pending: List<String>? = null,
    val permanent: List<String>? = null,
    val vin: VinDecoder.Result? = null,
    val error: String? = null,
)

/** All Simulator actions, so the stateless screen can be tested control by control. */
interface SimulatorActions {
    fun setUseSimulatedDongle(on: Boolean)
    fun update(transform: (SimulatorState) -> SimulatorState)
    fun linkConnected()
    fun linkDisconnected()
    fun injectDtc(code: String, list: DtcList = DtcList.STORED): Boolean
    fun setVin(vin: String)
    fun readDiagnostics()
    fun clearDtcs()
    fun setBackendMode(mode: BackendMode)
    fun startScript(id: String)
    fun stopScript()
    /** Raises the crash prompt as if the accelerometer had detected an impact (M7, #56). */
    fun injectImpact()
    fun reset()
}

@HiltViewModel
class SimulatorViewModel @Inject constructor(
    private val controller: SimulatorController,
    private val dongles: ObdDongleProvider,
    private val linkEvents: DongleLinkEvents,
    private val backend: BackendConfigRepository,
    private val manager: ObdConnectionManager,
    private val player: DriveScriptPlayer,
    telemetry: TelemetrySource,
    private val crash: io.github.neotheon3.orangebox.core.driving.crash.CrashMonitor,
) : ViewModel(), SimulatorActions {
    private val readout = MutableStateFlow<DiagnosticsReadout?>(null)

    val uiState: StateFlow<SimulatorUiState> = combine(
        combine(controller.state, manager.state, manager.lastLinkEvent, backend.config, dongles.selected) { sim, conn, link, cfg, selected ->
            SimulatorUiState(sim, conn, link, cfg.mode, selected?.address == DongleLinkEvent.SIMULATED_ADDRESS)
        },
        telemetry.latest,
        readout,
    ) { base, snap, r -> base.copy(telemetry = snap, readout = r) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SimulatorUiState(sim = controller.state.value))

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
    override fun injectDtc(code: String, list: DtcList) = controller.addDtc(code, list)
    override fun setVin(vin: String) = controller.setVin(vin)

    override fun readDiagnostics() {
        viewModelScope.launch {
            readout.value = try {
                manager.withSession { s ->
                    DiagnosticsReadout(
                        stored = s.readDtcs(DtcKind.STORED),
                        pending = s.readDtcs(DtcKind.PENDING),
                        permanent = s.readDtcs(DtcKind.PERMANENT),
                        vin = s.readVin(),
                    )
                } ?: DiagnosticsReadout(error = "Not connected")
            } catch (e: ObdError) {
                DiagnosticsReadout(error = e.describe())
            }
        }
    }
    override fun clearDtcs() = controller.clearDtcs()
    override fun setBackendMode(mode: BackendMode) { viewModelScope.launch { backend.setMode(mode) } }

    override fun startScript(id: String) { DriveScript.byId(id)?.let(player::start) }
    override fun stopScript() = player.stop()

    override fun injectImpact() = crash.injectImpact()

    override fun reset() {
        player.stop()
        readout.value = null
        val wasSimulated = uiState.value.simulatedSelected
        controller.reset()
        if (wasSimulated) viewModelScope.launch { dongles.select(null) }
    }
}
