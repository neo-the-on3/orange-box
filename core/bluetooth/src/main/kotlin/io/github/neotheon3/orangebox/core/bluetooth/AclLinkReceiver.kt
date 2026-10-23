package io.github.neotheon3.orangebox.core.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvent
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvents

/** Maps ACL broadcasts to [DongleLinkEvent]s; pure so it can be unit tested. */
object AclEventMapper {
    fun map(action: String?, address: String?): DongleLinkEvent? {
        val a = address ?: return null
        return when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> DongleLinkEvent.Connected(a)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> DongleLinkEvent.Disconnected(a)
            else -> null
        }
    }
}

/** Feeds ACL_CONNECTED/ACL_DISCONNECTED into [DongleLinkEvents]; the bus filters to the selected dongle. */
class AclLinkReceiver(private val events: DongleLinkEvents) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        AclEventMapper.map(intent.action, device?.address)?.let(events::report)
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // System broadcasts; must be exported to be received on API 34+.
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_EXPORTED)
    }
}
