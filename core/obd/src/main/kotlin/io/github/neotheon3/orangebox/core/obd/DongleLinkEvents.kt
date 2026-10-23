package io.github.neotheon3.orangebox.core.obd

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Bluetooth link-level events for a dongle, identified by its MAC address (or [SIMULATED_ADDRESS]). */
sealed interface DongleLinkEvent {
    val address: String

    data class Connected(override val address: String) : DongleLinkEvent
    data class Disconnected(override val address: String) : DongleLinkEvent

    companion object {
        /** Pseudo address of the debug-only simulated dongle. */
        const val SIMULATED_ADDRESS = "00:00:00:00:5E:00"
    }
}

/**
 * Single stream of link events fed by two sources: the Bluetooth ACL receiver and the debug
 * Simulator's "connected/disconnected" buttons. Events for anything other than the currently
 * selected dongle are dropped, so consumers cannot tell which source produced an event.
 */
class DongleLinkEvents(private val selectedAddress: () -> String?) {
    private val _events = MutableSharedFlow<DongleLinkEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DongleLinkEvent> = _events.asSharedFlow()

    /** Reports a raw event. Returns true if it was for the selected dongle and was emitted. */
    fun report(event: DongleLinkEvent): Boolean {
        val selected = selectedAddress() ?: return false
        if (!selected.equals(event.address, ignoreCase = true)) return false
        val normalised = when (event) {
            is DongleLinkEvent.Connected -> DongleLinkEvent.Connected(selected)
            is DongleLinkEvent.Disconnected -> DongleLinkEvent.Disconnected(selected)
        }
        return _events.tryEmit(normalised)
    }
}
