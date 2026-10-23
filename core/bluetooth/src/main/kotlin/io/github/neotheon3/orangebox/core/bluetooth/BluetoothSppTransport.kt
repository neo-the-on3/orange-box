package io.github.neotheon3.orangebox.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/** RFCOMM Serial Port Profile link to a bonded ELM327 dongle. */
class BluetoothSppTransport(
    private val context: Context,
    private val address: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ObdTransport {
    private var socket: BluetoothSocket? = null

    // Permission is checked explicitly by BluetoothPermissions.requireConnect before any call below.
    @SuppressLint("MissingPermission")
    override suspend fun open() = withContext(io) {
        BluetoothPermissions.requireConnect(context)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw ObdError.Io("Bluetooth not available on this device")
        if (!adapter.isEnabled) throw ObdError.Io("Bluetooth is off")
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw ObdError.NotPaired("Invalid dongle address")
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) throw ObdError.NotPaired("Dongle is not paired")
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            runInterruptible { s.connect() }
            socket = s
        } catch (e: SecurityException) {
            throw ObdError.PermissionDenied(BluetoothPermissions.connectPermission() ?: "BLUETOOTH")
        } catch (e: IOException) {
            throw ObdError.Io("Could not connect to dongle: ${e.message}", e)
        }
    }

    override suspend fun writeLine(line: String) = withContext(io) {
        val s = socket ?: throw ObdError.Io("Transport not open")
        try {
            s.outputStream.write("$line\r".toByteArray(Charsets.US_ASCII))
            s.outputStream.flush()
        } catch (e: IOException) {
            throw ObdError.Io("Write failed: ${e.message}", e)
        }
    }

    override suspend fun readUntilPrompt(timeoutMillis: Long): String = withContext(io) {
        val input = (socket ?: throw ObdError.Io("Transport not open")).inputStream
        val sb = StringBuilder()
        // Poll available() so the read stays cancellable; a blocking RFCOMM read cannot be interrupted.
        withTimeoutOrNull(timeoutMillis) {
            while (true) {
                val n = try { input.available() } catch (e: IOException) { throw ObdError.Io("Read failed: ${e.message}", e) }
                if (n <= 0) { delay(POLL_MS); continue }
                repeat(n) {
                    val b = input.read()
                    if (b < 0) throw ObdError.Io("Dongle closed the connection")
                    if (b.toChar() == '>') return@withTimeoutOrNull sb.toString()
                    if (b != 0) sb.append(b.toChar())
                }
            }
            @Suppress("UNREACHABLE_CODE") sb.toString()
        } ?: throw ObdError.Timeout()
    }

    override suspend fun close() {
        withContext(io) {
            try { socket?.close() } catch (_: IOException) {}
            socket = null
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val POLL_MS = 10L
    }
}
