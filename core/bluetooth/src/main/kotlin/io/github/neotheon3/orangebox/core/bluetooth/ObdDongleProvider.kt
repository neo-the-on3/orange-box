package io.github.neotheon3.orangebox.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.neotheon3.orangebox.core.datastore.DongleSelectionRepository
import io.github.neotheon3.orangebox.core.model.SelectedDongle
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvent
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdTransport
import io.github.neotheon3.orangebox.core.obd.ObdTransportProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A dongle the user can pick: a bonded Bluetooth device, or the debug-only simulated one. */
data class DongleCandidate(val address: String, val name: String, val simulated: Boolean = false)

/**
 * Supplies the simulated dongle in debug builds. Release binds [NoSimulatedDongle], so the
 * simulator never appears and its address is rejected as NotPaired.
 */
interface SimulatedDongleSource {
    val available: Boolean
    fun createTransport(): ObdTransport
}

object NoSimulatedDongle : SimulatedDongleSource {
    override val available = false
    override fun createTransport(): ObdTransport = throw ObdError.NotPaired("Simulator not available in this build")
}

interface ObdDongleProvider : ObdTransportProvider {
    val selected: Flow<SelectedDongle?>

    /** Bonded devices (plus the simulated dongle in debug). Throws [ObdError.PermissionDenied] without BLUETOOTH_CONNECT. */
    fun candidates(): List<DongleCandidate>

    suspend fun select(candidate: DongleCandidate?)
}

@Singleton
class DefaultObdDongleProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val selection: DongleSelectionRepository,
    private val simulated: SimulatedDongleSource,
) : ObdDongleProvider {
    override val selected: Flow<SelectedDongle?> = selection.selected

    @SuppressLint("MissingPermission") // checked by requireConnect
    override fun candidates(): List<DongleCandidate> {
        val sim = if (simulated.available) listOf(SIMULATED) else emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return sim
        BluetoothPermissions.requireConnect(context)
        val bonded = try {
            adapter.bondedDevices.orEmpty().map { DongleCandidate(it.address, it.name ?: it.address) }
        } catch (e: SecurityException) {
            throw ObdError.PermissionDenied(BluetoothPermissions.connectPermission() ?: "BLUETOOTH")
        }
        return sim + bonded.sortedBy { it.name }
    }

    override suspend fun select(candidate: DongleCandidate?) {
        selection.select(candidate?.let { SelectedDongle(it.address, it.name) })
    }

    override suspend fun create(): ObdTransport {
        val dongle = selection.selected.first() ?: throw ObdError.NotPaired("No OBD dongle selected")
        return if (dongle.address.equals(DongleLinkEvent.SIMULATED_ADDRESS, ignoreCase = true)) {
            if (!simulated.available) throw ObdError.NotPaired("Simulator not available in this build")
            simulated.createTransport()
        } else {
            BluetoothSppTransport(context, dongle.address)
        }
    }

    companion object {
        val SIMULATED = DongleCandidate(DongleLinkEvent.SIMULATED_ADDRESS, "Simulated ELM327", simulated = true)
    }
}
