package io.github.neotheon3.orangebox.core.driving.alerts

import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.model.AlertKind
import io.github.neotheon3.orangebox.core.model.AlertPreferences
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Plays an alert. The app's implementation uses Orange Box's own tones and, optionally, text-to-speech. */
interface AlertOutput {
    fun play(kind: AlertKind, sound: Boolean, voice: Boolean)
}

/**
 * Sounds driving alerts while connected (M8 #59): live telemetry → [AlertPolicy] → [AlertOutput], with
 * the toggles from Settings applied at play time. A new connection starts a fresh policy.
 */
@Singleton
class AlertMonitor(
    private val scope: CoroutineScope,
    private val connection: ObdConnection,
    private val telemetry: TelemetrySource,
    private val prefs: AppPreferencesRepository,
    private val output: AlertOutput,
    private val clock: () -> Long,
) {
    @Inject constructor(
        @ApplicationScope scope: CoroutineScope,
        connection: ObdConnection,
        telemetry: TelemetrySource,
        prefs: AppPreferencesRepository,
        output: AlertOutput,
    ) : this(scope, connection, telemetry, prefs, output, System::currentTimeMillis)

    private var installed = false

    /** Starts listening. Idempotent. */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        val alerts: StateFlow<AlertPreferences> = prefs.preferences.map { it.alerts }.distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, AlertPreferences())
        scope.launch {
            connection.state.map { it is ObdConnectionState.Connected }.distinctUntilChanged().collectLatest { connected ->
                if (!connected) return@collectLatest
                val policy = AlertPolicy()
                telemetry.latest.filterNotNull().collect { snapshot ->
                    val fired = policy.onSample(snapshot.instant, clock())
                    if (fired.isEmpty()) return@collect
                    val a = alerts.value
                    for (kind in fired) {
                        if (!a.isEnabled(kind) || !(a.sound || a.voice)) continue
                        OrangeLog.i(OrangeLog.Areas.APP, "alert", "kind" to kind.name, "voice" to a.voice)
                        runCatching { output.play(kind, a.sound, a.voice) }
                    }
                }
            }
        }
    }
}
