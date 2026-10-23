package io.github.neotheon3.orangebox

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.neotheon3.orangebox.core.bluetooth.AclLinkReceiver
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvents
import javax.inject.Inject

@HiltAndroidApp
class OrangeBoxApplication : Application() {
    @Inject lateinit var dongleLinkEvents: DongleLinkEvents

    override fun onCreate() {
        super.onCreate()
        // Bluetooth ACL connect/disconnect for the selected dongle feeds the same bus as the Simulator buttons.
        AclLinkReceiver(dongleLinkEvents).register(this)
    }
}
