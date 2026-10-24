package io.github.neotheon3.orangebox.core.driving

import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The current drive, as far as the UI is concerned. "End driving" is undoable: [requestEnd] starts a
 * [UNDO_WINDOW_MILLIS] window ([pendingEnd] = true) and only then commits, i.e. disconnects the dongle so
 * the drive is over. [undoEnd] inside the window keeps driving.
 */
@Singleton
class DriveSession @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val connection: ObdConnection,
) {
    private val _pendingEnd = MutableStateFlow(false)
    val pendingEnd: StateFlow<Boolean> = _pendingEnd.asStateFlow()

    private var commitJob: Job? = null
    private val onEnd = mutableListOf<() -> Unit>()

    /** Runs when an end is committed (after the undo window). */
    fun addOnEndListener(listener: () -> Unit) = synchronized(onEnd) { onEnd += listener }

    @Synchronized
    fun requestEnd() {
        if (_pendingEnd.value) return
        _pendingEnd.value = true
        OrangeLog.i(OrangeLog.Areas.APP, "drive_end_requested", "undo_ms" to UNDO_WINDOW_MILLIS)
        commitJob = scope.launch {
            delay(UNDO_WINDOW_MILLIS)
            commit()
        }
    }

    @Synchronized
    fun undoEnd() {
        if (!_pendingEnd.value) return
        commitJob?.cancel()
        commitJob = null
        _pendingEnd.value = false
        OrangeLog.i(OrangeLog.Areas.APP, "drive_end_undone")
    }

    private fun commit() {
        synchronized(this) {
            if (!_pendingEnd.value) return
            commitJob = null
            _pendingEnd.value = false
        }
        synchronized(onEnd) { onEnd.toList() }.forEach { it() }
        connection.disconnect()
        OrangeLog.i(OrangeLog.Areas.APP, "drive_ended")
    }

    companion object {
        const val UNDO_WINDOW_MILLIS = 5_000L
    }
}
