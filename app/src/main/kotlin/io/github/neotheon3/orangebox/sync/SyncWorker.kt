package io.github.neotheon3.orangebox.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.database.RoomSyncStore
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.datastore.SyncError
import io.github.neotheon3.orangebox.core.datastore.SyncStatus
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.sync.SyncEngine
import io.github.neotheon3.orangebox.core.sync.SyncStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** What a sync run asks WorkManager to do next. */
sealed interface SyncOutcome {
    /** Done, or nothing to do (Off, Remote without a session). */
    data object Done : SyncOutcome

    /** Run again after that many seconds: the server's `Retry-After` (clamped), or our backoff without one. */
    data class RetryAfter(val seconds: Long) : SyncOutcome

    /** Won't fix itself by retrying (other 4xx, signed out, repeated recovery, too many failures in a row). */
    data class Failed(val reason: String) : SyncOutcome
}

/**
 * One backend sync run ([SyncEngine]). Off: nothing, and no client is built. Remote without a session: nothing
 * (sign in first); Remote syncs only the signed-in member's trips, and nothing while the sign-in choice about
 * the phone's other trips is unanswered. Retryable failures (`503`, `429`, network, `409` in flight) run again after the server's
 * `Retry-After` (e.g. `429 upload_in_progress` 1 s, `503 uploads_busy` 5 s), clamped to [BACKOFF_CAP_SECONDS];
 * without one (a proxy's 502/503/504, network) after an exponential backoff with jitter from
 * [BACKOFF_BASE_SECONDS], capped at [BACKOFF_CAP_SECONDS]. Other errors end the run.
 */
class SyncRunner(
    private val backendConfig: BackendConfigRepository,
    private val clients: BackendClients,
    private val store: RoomSyncStore,
    private val status: SyncStatusStore?,
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
    private val clock: () -> Long,
) {
    @Inject constructor(backendConfig: BackendConfigRepository, clients: BackendClients, store: RoomSyncStore, status: SyncStatusStore) :
        this(backendConfig, clients, store, status, clock = System::currentTimeMillis)

    /** Tests without the status table. */
    constructor(backendConfig: BackendConfigRepository, clients: BackendClients, store: RoomSyncStore) :
        this(backendConfig, clients, store, null, clock = System::currentTimeMillis)

    internal var storeOverride: SyncStore? = null

    /** Failures in a row when there is no status table (tests). */
    private var failuresInMemory = 0

    suspend fun run(): SyncOutcome {
        val config = backendConfig.config.first()
        val api = clients.forConfig(config) ?: return SyncOutcome.Done
        val session = clients.sessionStore.read()
        if (config.mode == BackendMode.REMOTE && session == null) return SyncOutcome.Done
        // Just signed in with other trips on the phone: nothing syncs until merge / replace / cancel is chosen.
        if (config.mode == BackendMode.REMOTE && store.choicePending()) return SyncOutcome.Done
        // Remote syncs the member's own trips; Fake has no account (the trips recorded without one).
        val account = if (config.mode == BackendMode.REMOTE) session?.memberId else null
        val previous = status?.read()?.consecutiveFailures ?: failuresInMemory
        val outcome = runEngine(api, config.mode, previous, account)
        failuresInMemory = outcome.second.consecutiveFailures
        status?.write(outcome.second)
        return outcome.first
    }

    private suspend fun runEngine(
        api: io.github.neotheon3.orangebox.core.network.BackendApi,
        mode: BackendMode,
        failures: Int,
        account: String?,
    ): Pair<SyncOutcome, SyncStatus> {
        val now = clock()
        return try {
            val r = SyncEngine(api, storeOverride ?: store, account = account).run()
            OrangeLog.i(
                OrangeLog.Areas.BACKEND, "sync_ok", "mode" to mode.wireName, "pulled" to r.pulled, "uploaded" to r.uploaded,
                "deleted" to r.deletedByServer, "rejected" to r.rejected, "recovery" to (r.recovery?.name ?: "none"), "dropped" to r.dropped,
                "telemetry_batches" to r.telemetryBatches, "telemetry_rejected" to r.telemetryRejected,
                "dtc_reports" to r.dtcReports, "dtc_dropped" to r.dtcReportsDropped, "not_implemented" to r.notImplemented.sorted().joinToString(","),
            )
            SyncOutcome.Done to SyncStatus(now, ok = true, uploadedTrips = r.uploaded, pulledTrips = r.pulled, telemetryBatches = r.telemetryBatches)
        } catch (e: ApiError) {
            OrangeLog.w(OrangeLog.Areas.BACKEND, "sync_failed", "error" to (e.message ?: "?"), "retry" to e.isRetryable)
            val inARow = failures + 1
            val retryAfter = (e as? ApiError.Http)?.retryAfterSeconds
            val outcome = when {
                !e.isRetryable -> SyncOutcome.Failed(e.message ?: "error")
                inARow > MAX_ATTEMPTS -> SyncOutcome.Failed("gave up after $failures attempts")
                retryAfter != null -> SyncOutcome.RetryAfter(clampRetryAfter(retryAfter))
                else -> SyncOutcome.RetryAfter(backoffSeconds(inARow, random()))
            }
            outcome to SyncStatus(
                now, ok = false, error = errorOf(e), willRetry = outcome !is SyncOutcome.Failed,
                consecutiveFailures = if (outcome is SyncOutcome.Failed) 0 else inARow,
            )
        } catch (e: IllegalStateException) {
            OrangeLog.w(OrangeLog.Areas.BACKEND, "sync_failed", "error" to (e.message ?: "?"), "retry" to false)
            SyncOutcome.Failed(e.message ?: "error") to SyncStatus(now, ok = false, error = SyncError.RECOVERY)
        }
    }

    private fun errorOf(e: ApiError): SyncError = when (e) {
        is ApiError.Network -> SyncError.NETWORK
        is ApiError.NotSignedIn -> SyncError.SIGNED_OUT
        is ApiError.Http -> when {
            e.status == 429 || e.status >= 500 -> SyncError.SERVER_BUSY
            e.status == 401 -> SyncError.SIGNED_OUT
            else -> SyncError.REJECTED
        }
    }

    companion object {
        /** Retries in a row before the one-off gives up (the 6-hourly sync still runs). */
        const val MAX_ATTEMPTS = 10
        const val BACKOFF_BASE_SECONDS = 30L
        const val BACKOFF_CAP_SECONDS = 3_600L

        fun clampRetryAfter(seconds: Long): Long = seconds.coerceIn(1, BACKOFF_CAP_SECONDS)

        /**
         * Exponential backoff with "equal jitter": failure n waits between half and all of
         * min(cap, base × 2^(n−1)), so devices that failed together don't retry together.
         */
        fun backoffSeconds(failuresInARow: Int, random: Double): Long {
            val exp = (failuresInARow - 1).coerceIn(0, 20)
            val ceiling = minOf(BACKOFF_CAP_SECONDS, BACKOFF_BASE_SECONDS shl exp)
            val half = ceiling / 2.0
            return (half + half * random.coerceIn(0.0, 1.0)).toLong().coerceAtLeast(1)
        }
    }
}

/** WorkManager wrapper around [SyncRunner]. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun runner(): SyncRunner
        fun scheduler(): SyncScheduler
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        return when (val outcome = deps.runner().run()) {
            SyncOutcome.Done -> Result.success()
            is SyncOutcome.RetryAfter -> {
                deps.scheduler().syncSoon(delaySeconds = outcome.seconds)
                Result.success()
            }
            is SyncOutcome.Failed -> Result.failure()
        }
    }
}
