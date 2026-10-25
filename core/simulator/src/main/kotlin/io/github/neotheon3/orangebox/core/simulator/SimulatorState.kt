package io.github.neotheon3.orangebox.core.simulator

import kotlinx.serialization.Serializable

/** Every knob of the simulated ELM327 dongle. Persisted as JSON by the debug Simulator screen. */
@Serializable
data class SimulatorState(
    val useSimulatedDongle: Boolean = false,
    val paired: Boolean = true,
    val engineOn: Boolean = true,
    val speedKph: Int = 0,
    val rpm: Int = IDLE_RPM,
    val coolantC: Int = 85,
    val fuelPercent: Int = 60,
    /** Accelerator/throttle, %. Scripts drive it; 0 while coasting above 20 km/h means decel fuel cut. */
    val throttlePercent: Int = 0,
    val dtcs: List<String> = emptyList(),
    val pendingDtcs: List<String> = emptyList(),
    /** Permanent DTCs (mode 0A). Like a real ECU, mode 04 does not clear them. */
    val permanentDtcs: List<String> = emptyList(),
    /** The ECU doesn't implement mode 0A: answers `7F 0A 11` like many pre-2010 / BS-IV cars (#196). */
    val permanentUnsupported: Boolean = false,
    /** VIN answered to 09 02. Not validated, so invalid VIN handling can be tested. */
    val vin: String = VIN,
    val milOn: Boolean = false,
    val connectionFailure: Boolean = false,
    val unsupportedProtocol: Boolean = false,
    val slowResponses: Boolean = false,
    val garbledResponses: Boolean = false,
    /** Every ATDPN reply is garbage, so the protocol can never be determined (tests the fail-after-re-queries path). */
    val garbledProtocolReplies: Boolean = false,
    /**
     * Seconds the simulated dongle takes to open its link (0 = immediate). Lets testers background the app
     * between tapping connect and the dongle connecting, to see the background "Start driving" notification.
     */
    val connectDelaySeconds: Int = 0,
    /** Name of the drive script being played; not persisted (playback does not resume after restart). */
    @kotlinx.serialization.Transient val runningScript: String? = null,
) {
    /** Failure toggles that are on, for the prominent warning banner. */
    val activeFailures: List<String>
        get() = buildList {
            if (!paired) add("Unpaired")
            if (connectionFailure) add("Connection failure")
            if (unsupportedProtocol) add("Unsupported protocol")
            if (slowResponses) add("Slow (1.5 s)")
            if (garbledResponses) add("Garbled (~30%)")
            if (garbledProtocolReplies) add("Garbled ATDPN (always)")
            if (connectDelaySeconds > 0) add("Delay connect (${connectDelaySeconds} s)")
        }

    /** RPM the dongle reports: 0 with the engine off. */
    val effectiveRpm: Int get() = if (engineOn) rpm else 0

    companion object {
        const val IDLE_RPM = 800
        /** Synthetic VIN with a valid ISO 3779 check digit; not a real vehicle. */
        const val VIN = "2SMLTRB02RB000001"
        val THROTTLE_RANGE = 0..100
        val SPEED_RANGE = 0..250
        val RPM_RANGE = 0..8000
        val COOLANT_RANGE = -40..215
        val FUEL_RANGE = 0..100
        /** The Simulator screen's "Delay connect" toggle. */
        const val CONNECT_DELAY_SECONDS = 10
    }
}
