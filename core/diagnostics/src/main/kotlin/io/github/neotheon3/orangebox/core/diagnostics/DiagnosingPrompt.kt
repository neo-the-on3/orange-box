package io.github.neotheon3.orangebox.core.diagnostics

import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The "Car Diagnosing…" prompt at drive start (#50, docs/ui/LEGACY_OBD_UI.md §5.3). */
sealed interface DiagnosingPrompt {
    data object Hidden : DiagnosingPrompt
    data object Scanning : DiagnosingPrompt
    /** Auto-dismisses after [DiagnosingPromptController.NO_TROUBLES_MILLIS]. */
    data object NoTroubles : DiagnosingPrompt
    data class Warning(val codeCount: Int, val milOn: Boolean) : DiagnosingPrompt
}

/**
 * Runs one diagnostics scan per drive and exposes the prompt state. A drive starts with the first
 * Connected; reconnects after a dropped link (LinkLost, retries) belong to the same drive, and only a
 * session that ended (Idle, e.g. "End driving", or Failed) lets the next Connected prompt again.
 * The prompt is non-blocking: [dismiss] hides it at any point, and a failed scan just hides it.
 */
@Singleton
class DiagnosingPromptController @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val connection: ObdConnection,
    private val diagnostics: DiagnosticsRepository,
) {
    private val _prompt = MutableStateFlow<DiagnosingPrompt>(DiagnosingPrompt.Hidden)
    val prompt: StateFlow<DiagnosingPrompt> = _prompt.asStateFlow()

    private var promptedThisDrive = false
    private var job: Job? = null
    private var installed = false

    /** Starts watching the connection. Idempotent. */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        scope.launch {
            connection.state.collect { state ->
                when (state) {
                    is ObdConnectionState.Connected -> if (!promptedThisDrive) {
                        promptedThisDrive = true
                        job = scope.launch { run() }
                    }
                    ObdConnectionState.Idle, is ObdConnectionState.Failed -> {
                        promptedThisDrive = false
                        job?.cancel()
                        if (_prompt.value == DiagnosingPrompt.Scanning) _prompt.value = DiagnosingPrompt.Hidden
                    }
                    else -> Unit
                }
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        _prompt.value = DiagnosingPrompt.Hidden
    }

    private suspend fun run() {
        _prompt.value = DiagnosingPrompt.Scanning
        // Let the sweep run for a moment even when the read is instant (the simulator).
        val result = coroutineScope {
            val minimum = async { delay(MIN_SCAN_MILLIS) }
            diagnostics.scan().also { minimum.await() }
        }
        if (_prompt.value != DiagnosingPrompt.Scanning) return // dismissed meanwhile
        val report = (result as? ScanResult.Success)?.report
        OrangeLog.i(OrangeLog.Areas.OBD, "drive_start_diagnosis", "result" to result::class.simpleName, "troubles" to report?.hasTroubles)
        _prompt.value = when {
            report == null -> DiagnosingPrompt.Hidden
            report.hasTroubles -> DiagnosingPrompt.Warning(report.codes.map { it.code }.distinct().size, report.milOn == true)
            else -> DiagnosingPrompt.NoTroubles
        }
        if (_prompt.value == DiagnosingPrompt.NoTroubles) {
            delay(NO_TROUBLES_MILLIS)
            if (_prompt.value == DiagnosingPrompt.NoTroubles) _prompt.value = DiagnosingPrompt.Hidden
        }
    }

    companion object {
        const val MIN_SCAN_MILLIS = 2_500L
        const val NO_TROUBLES_MILLIS = 2_500L
    }
}
