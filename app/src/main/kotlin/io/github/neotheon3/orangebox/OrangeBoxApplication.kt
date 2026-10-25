package io.github.neotheon3.orangebox

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.neotheon3.orangebox.core.bluetooth.AclLinkReceiver
import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OrangeBoxApplication : Application() {
    @Inject lateinit var dongleLinkEvents: DongleLinkEvents
    @Inject lateinit var backendConfig: BackendConfigRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    @Inject lateinit var drivingController: io.github.neotheon3.orangebox.driving.DrivingController
    @Inject lateinit var diagnosingPrompt: io.github.neotheon3.orangebox.core.diagnostics.DiagnosingPromptController

    override fun onCreate() {
        AndroidLogSink.install(debug = BuildConfig.DEBUG)
        super.onCreate()
        OrangeLog.i(OrangeLog.Areas.APP, "start", "version" to BuildConfig.VERSION_NAME, "sdk" to android.os.Build.VERSION.SDK_INT)
        // Bluetooth ACL connect/disconnect for the selected dongle feeds the same bus as the Simulator buttons.
        AclLinkReceiver(dongleLinkEvents).register(this)
        // Dongle connect -> driving service + trip recording (M4).
        drivingController.install(this)
        // One "Car Diagnosing…" scan per drive (M6, #50).
        diagnosingPrompt.install()
        appScope.launch {
            backendConfig.config.map { it.mode }.distinctUntilChanged().collect {
                OrangeLog.i(OrangeLog.Areas.BACKEND, "mode", "mode" to it.wireName)
            }
        }
    }
}
