package io.github.neotheon3.orangebox.core.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.neotheon3.orangebox.core.obd.ObdError

internal object BluetoothPermissions {
    /** On API 31+ BLUETOOTH_CONNECT is a runtime permission; on 24-30 the install-time BLUETOOTH suffices. */
    fun connectPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null

    fun hasConnect(context: Context): Boolean = connectPermission()?.let {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    } ?: true

    fun requireConnect(context: Context) {
        if (!hasConnect(context)) throw ObdError.PermissionDenied(connectPermission()!!)
    }
}
