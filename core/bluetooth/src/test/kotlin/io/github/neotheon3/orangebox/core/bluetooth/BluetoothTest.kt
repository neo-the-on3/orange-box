package io.github.neotheon3.orangebox.core.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.datastore.DongleSelectionRepository
import io.github.neotheon3.orangebox.core.model.SelectedDongle
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvent
import io.github.neotheon3.orangebox.core.obd.ObdError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private class MemSelection(initial: SelectedDongle? = null) : DongleSelectionRepository {
    override val selected = MutableStateFlow(initial)
    override suspend fun select(dongle: SelectedDongle?) { selected.value = dongle }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BluetoothTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `missing BLUETOOTH_CONNECT is a typed error on API 31+`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        try {
            BluetoothSppTransport(app, "AA:BB:CC:DD:EE:FF").open(); fail()
        } catch (e: ObdError.PermissionDenied) {
            assertEquals(Manifest.permission.BLUETOOTH_CONNECT, e.permission)
            assertTrue(e.isTerminal)
        }
        try {
            DefaultObdDongleProvider(app, MemSelection(), NoSimulatedDongle).candidates(); fail()
        } catch (_: ObdError.PermissionDenied) {}
    }

    @Test @Config(sdk = [24])
    fun `API 24 needs no runtime permission`() {
        assertNull(BluetoothPermissions.connectPermission())
        assertTrue(BluetoothPermissions.hasConnect(app))
    }

    @Test fun `no selection is NotPaired`() = runTest {
        try { DefaultObdDongleProvider(app, MemSelection(), NoSimulatedDongle).create(); fail() } catch (_: ObdError.NotPaired) {}
    }

    @Test fun `simulated address is rejected without a simulator`() = runTest {
        val p = DefaultObdDongleProvider(app, MemSelection(SelectedDongle(DongleLinkEvent.SIMULATED_ADDRESS, "sim")), NoSimulatedDongle)
        try { p.create(); fail() } catch (_: ObdError.NotPaired) {}
    }

    @Test fun `acl actions map to link events`() {
        assertEquals(DongleLinkEvent.Connected("AA"), AclEventMapper.map(BluetoothDevice.ACTION_ACL_CONNECTED, "AA"))
        assertEquals(DongleLinkEvent.Disconnected("AA"), AclEventMapper.map(BluetoothDevice.ACTION_ACL_DISCONNECTED, "AA"))
        assertNull(AclEventMapper.map("other", "AA"))
        assertNull(AclEventMapper.map(BluetoothDevice.ACTION_ACL_CONNECTED, null))
    }
}
