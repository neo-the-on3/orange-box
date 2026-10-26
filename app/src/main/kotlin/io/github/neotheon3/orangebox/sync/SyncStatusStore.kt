package io.github.neotheon3.orangebox.sync

import io.github.neotheon3.orangebox.core.database.SyncDao
import io.github.neotheon3.orangebox.core.database.SyncStateEntity
import io.github.neotheon3.orangebox.core.datastore.SyncError
import io.github.neotheon3.orangebox.core.datastore.SyncStatus
import io.github.neotheon3.orangebox.core.network.BackendJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** The last sync run's outcome, kept in `sync_state` (cleared with it on sign-out). */
@Singleton
class SyncStatusStore @Inject constructor(private val dao: SyncDao) {

    @Serializable private data class Stored(
        val atMillis: Long,
        val ok: Boolean,
        val error: String? = null,
        val uploadedTrips: Int = 0,
        val pulledTrips: Int = 0,
        val telemetryBatches: Int = 0,
        val willRetry: Boolean = false,
        val consecutiveFailures: Int = 0,
    )

    val status: Flow<SyncStatus?> = dao.observeState(KEY).map { it?.let(::decode) }.distinctUntilChanged()

    suspend fun write(status: SyncStatus) {
        val stored = Stored(
            status.atMillis, status.ok, status.error?.name, status.uploadedTrips, status.pulledTrips, status.telemetryBatches, status.willRetry,
            status.consecutiveFailures,
        )
        dao.putState(SyncStateEntity(KEY, BackendJson.encodeToString(Stored.serializer(), stored)))
    }

    suspend fun read(): SyncStatus? = dao.state(KEY)?.let(::decode)

    private fun decode(json: String): SyncStatus? = runCatching { BackendJson.decodeFromString(Stored.serializer(), json) }.getOrNull()?.let {
        SyncStatus(
            atMillis = it.atMillis, ok = it.ok,
            error = it.error?.let { e -> SyncError.entries.firstOrNull { x -> x.name == e } ?: SyncError.UNKNOWN },
            uploadedTrips = it.uploadedTrips, pulledTrips = it.pulledTrips, telemetryBatches = it.telemetryBatches, willRetry = it.willRetry,
            consecutiveFailures = it.consecutiveFailures,
        )
    }

    companion object {
        const val KEY = "last_sync_status"
    }
}
