package io.github.neotheon3.orangebox.feature.driving

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.driving.DeviceCountry
import io.github.neotheon3.orangebox.core.driving.GeoFix
import io.github.neotheon3.orangebox.core.driving.TripRecorder
import io.github.neotheon3.orangebox.core.driving.crash.CrashMonitor
import io.github.neotheon3.orangebox.core.driving.crash.CrashPrompt
import io.github.neotheon3.orangebox.core.driving.crash.EmergencyIntents
import io.github.neotheon3.orangebox.core.driving.crash.EmergencyMessage
import io.github.neotheon3.orangebox.core.model.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Who the prompt reaches: the number the dialer gets and the (optional) SMS recipient. */
data class EmergencyTargets(
    val dialNumber: String,
    val smsNumber: String? = null,
    val smsName: String? = null,
)

/**
 * Glue between [CrashMonitor] and the screen / notification: resolves the numbers from Settings, builds
 * the SMS text with the last location, and opens the dialer or SMS app (never calls or sends itself).
 */
@Singleton
class CrashPromptController(
    val monitor: CrashMonitor,
    scope: CoroutineScope,
    prefs: kotlinx.coroutines.flow.Flow<AppPreferences>,
    private val emergencyNumber: (AppPreferences) -> String,
    private val lastFix: () -> GeoFix?,
) {
    @Inject constructor(
        monitor: CrashMonitor,
        @ApplicationScope scope: CoroutineScope,
        prefs: AppPreferencesRepository,
        country: DeviceCountry,
        recorder: TripRecorder,
    ) : this(monitor, scope, prefs.preferences, { country.emergencyNumber(it.dashboard) }, { recorder.lastFix })

    val prompt: StateFlow<CrashPrompt?> get() = monitor.prompt

    val targets: StateFlow<EmergencyTargets> = prefs.map { p -> targetsOf(p) }
        .stateIn(scope, SharingStarted.Eagerly, EmergencyTargets(EMERGENCY_NUMBER))

    /** Dial the dashboard's emergency number; text the emergency contact from Settings (#60), if any. */
    private fun targetsOf(p: AppPreferences) = EmergencyTargets(
        dialNumber = emergencyNumber(p),
        smsNumber = p.crash.contactNumber,
        smsName = p.crash.contactNumber?.let { p.crash.contactName ?: it },
    )

    fun cancel() = monitor.cancel()

    /** The SMS text for now. */
    fun message(context: Context): String = EmergencyMessage.compose(
        intro = context.getString(R.string.crash_sms_intro),
        fix = lastFix(),
        locationLabel = context.getString(R.string.crash_sms_location),
        noLocation = context.getString(R.string.crash_sms_no_location),
    )

    fun dialIntent(): Intent = EmergencyIntents.dial(targets.value.dialNumber)

    fun smsIntent(context: Context): Intent = EmergencyIntents.sms(targets.value.smsNumber, message(context))

    /** Opens the dialer for prompt [id] and ends the prompt. */
    fun dial(context: Context, id: Long) = open(context, id, dialIntent(), "dial")

    /** Opens the SMS app for prompt [id] and ends the prompt. */
    fun sms(context: Context, id: Long) = open(context, id, smsIntent(context), "sms")

    private fun open(context: Context, id: Long, intent: Intent, what: String) {
        try {
            context.startActivity(intent)
            OrangeLog.i(OrangeLog.Areas.APP, "crash_prompt_opened", "app" to what)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, if (what == "dial") R.string.emergency_no_dialer else R.string.crash_no_sms_app, Toast.LENGTH_LONG).show()
        }
        monitor.completed(id)
    }
}
