package io.github.neotheon3.orangebox.core.driving.crash

import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.model.CrashPreferences
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What raised the prompt. */
enum class CrashTrigger {
    /** The accelerometer detected an impact while driving. */
    IMPACT,

    /** The debug Simulator's "Inject impact" button (behaves exactly like [IMPACT]). */
    SIMULATED,

    /** The dashboard's hazard button: no countdown, the user picks an action. */
    MANUAL,
}

/**
 * The crash prompt on screen. [remainingSeconds] counts down to 0 for an impact; [timedOut] is set when
 * it reached 0 with no answer, and the UI (or the notification) then opens the dialer. Manual prompts
 * have no countdown ([countdownSeconds] null).
 */
data class CrashPrompt(
    val id: Long,
    val trigger: CrashTrigger,
    val peakG: Double? = null,
    val countdownSeconds: Int? = null,
    val remainingSeconds: Int? = countdownSeconds,
    val timedOut: Boolean = false,
) {
    val counting: Boolean get() = remainingSeconds != null && !timedOut
}

/**
 * Crash detection (M7, #53/#54). While driving (connected, and for [AFTER_DRIVE_MILLIS] after the link
 * drops, since a collision can cut the dongle's power) and with detection on in Settings, it reads the
 * [MotionSource] through an [ImpactDetector]. An impact only counts if the car was moving at least
 * [MIN_SPEED_KPH] in the last [RECENT_MOVING_MILLIS] (a phone knocked about in a parked car is not a crash).
 *
 * Nothing is ever called or sent: the prompt counts down, and on timeout or confirmation only the
 * dialer / SMS app opens with the number and message prefilled (no CALL_PHONE / SEND_SMS permission).
 */
@Singleton
class CrashMonitor(
    private val scope: CoroutineScope,
    private val connection: ObdConnection,
    private val telemetry: TelemetrySource,
    private val prefs: AppPreferencesRepository,
    private val motion: MotionSource,
    private val clock: () -> Long,
) {
    @Inject constructor(
        @ApplicationScope scope: CoroutineScope,
        connection: ObdConnection,
        telemetry: TelemetrySource,
        prefs: AppPreferencesRepository,
        motion: MotionSource,
    ) : this(scope, connection, telemetry, prefs, motion, System::currentTimeMillis)

    private val _prompt = MutableStateFlow<CrashPrompt?>(null)
    val prompt: StateFlow<CrashPrompt?> = _prompt.asStateFlow()

    /** Whether the motion source is being read right now (for tests and the log). */
    private val _watching = MutableStateFlow(false)
    val watching: StateFlow<Boolean> = _watching.asStateFlow()

    private var countdown: Job? = null
    private var nextId = 1L
    private var installed = false
    @Volatile private var lastMovingAt: Long? = null

    /** Starts watching. Idempotent. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        scope.launch {
            telemetry.latest.collect { s ->
                if ((s?.instant?.speedKph ?: 0.0) >= MIN_SPEED_KPH) lastMovingAt = clock()
            }
        }
        scope.launch {
            combine(driving(), prefs.preferences.map { it.crash }.distinctUntilChanged()) { d, c -> if (d && c.detectionEnabled) c else null }
                .distinctUntilChanged()
                .collectLatest { crash -> if (crash != null) watch(crash) else _watching.value = false }
        }
    }

    /** True while connected, and for [AFTER_DRIVE_MILLIS] after a connected link went away. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun driving() = connection.state.map { it is ObdConnectionState.Connected }.distinctUntilChanged()
        .transformLatest { connected ->
            if (connected) {
                emit(true)
                wasConnected = true
            } else if (wasConnected) {
                delay(AFTER_DRIVE_MILLIS)
                emit(false)
            } else {
                emit(false)
            }
        }

    private var wasConnected = false

    private suspend fun watch(crash: CrashPreferences) {
        val detector = ImpactDetector(crash.thresholdG)
        _watching.value = true
        OrangeLog.i(OrangeLog.Areas.APP, "crash_watch_on", "threshold_g" to crash.thresholdG)
        try {
            motion.samples().collect { s ->
                val impact = detector.onSample(s) ?: return@collect
                val moving = lastMovingAt?.let { clock() - it <= RECENT_MOVING_MILLIS } == true
                OrangeLog.i(OrangeLog.Areas.APP, "impact", "peak_g" to "%.1f".format(impact.peakG), "moving" to moving)
                if (moving) raise(CrashTrigger.IMPACT, impact.peakG, crash.countdownSeconds)
            }
        } finally {
            _watching.value = false
        }
    }

    /** Debug Simulator: behaves like a detected impact, without the sensor or the speed check. */
    fun injectImpact(peakG: Double = SIMULATED_PEAK_G) {
        scope.launch { raise(CrashTrigger.SIMULATED, peakG, prefs.preferences.first().crash.countdownSeconds) }
    }

    /** Dashboard hazard button: the same prompt without a countdown. */
    fun showManual() {
        if (_prompt.value != null) return
        _prompt.value = CrashPrompt(nextId++, CrashTrigger.MANUAL)
        OrangeLog.i(OrangeLog.Areas.APP, "crash_prompt", "trigger" to CrashTrigger.MANUAL.name)
    }

    @Synchronized
    private fun raise(trigger: CrashTrigger, peakG: Double?, seconds: Int) {
        val current = _prompt.value
        if (current != null && current.trigger != CrashTrigger.MANUAL) return // one prompt at a time
        val p = CrashPrompt(nextId++, trigger, peakG, seconds)
        _prompt.value = p
        OrangeLog.i(OrangeLog.Areas.APP, "crash_prompt", "trigger" to trigger.name, "countdown_s" to seconds)
        countdown?.cancel()
        countdown = scope.launch {
            var left = seconds
            while (left > 0) {
                delay(1_000)
                left--
                _prompt.value = _prompt.value?.takeIf { it.id == p.id }?.copy(remainingSeconds = left) ?: return@launch
            }
            _prompt.value = _prompt.value?.takeIf { it.id == p.id }?.copy(timedOut = true)
            OrangeLog.i(OrangeLog.Areas.APP, "crash_prompt_timeout")
        }
    }

    /** "I'm OK": dismisses the prompt. */
    fun cancel() {
        val p = _prompt.value ?: return
        countdown?.cancel()
        _prompt.value = null
        OrangeLog.i(OrangeLog.Areas.APP, "crash_prompt_cancelled", "trigger" to p.trigger.name, "remaining_s" to p.remainingSeconds)
    }

    /** The dialer or SMS app was opened for prompt [id]; the prompt is done. */
    fun completed(id: Long) {
        if (_prompt.value?.id != id) return
        countdown?.cancel()
        _prompt.value = null
    }

    companion object {
        const val MIN_SPEED_KPH = 15.0
        const val RECENT_MOVING_MILLIS = 15_000L
        const val AFTER_DRIVE_MILLIS = 60_000L
        const val SIMULATED_PEAK_G = 6.5

        /** Unused source for builds/tests that never read motion. */
        val NO_MOTION = MotionSource { emptyFlow() }
    }
}
