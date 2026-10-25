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
    @Inject lateinit var crashMonitor: io.github.neotheon3.orangebox.core.driving.crash.CrashMonitor
    @Inject lateinit var crashNotifier: io.github.neotheon3.orangebox.crash.CrashNotifier
    @Inject lateinit var appVisibility: io.github.neotheon3.orangebox.crash.AppVisibility
    @Inject lateinit var alertMonitor: io.github.neotheon3.orangebox.core.driving.alerts.AlertMonitor

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
        // Impact detection while driving -> crash prompt, with a notification when not on screen (M7).
        appVisibility.install(this)
        crashMonitor.install()
        crashNotifier.install(this)
        // Overspeed / idling / harsh / check-engine alerts while connected (M8).
        alertMonitor.install()
        appScope.launch {
            backendConfig.config.map { it.mode }.distinctUntilChanged().collect {
                OrangeLog.i(OrangeLog.Areas.BACKEND, "mode", "mode" to it.wireName)
            }
        }
    }
}
