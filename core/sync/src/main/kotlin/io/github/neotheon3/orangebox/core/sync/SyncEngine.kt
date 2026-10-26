package io.github.neotheon3.orangebox.core.sync

import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.network.BackendApi
import io.github.neotheon3.orangebox.core.network.SyncWindowReason
import io.github.neotheon3.orangebox.core.network.TripBatchDto
import io.github.neotheon3.orangebox.core.network.TripDto
import java.util.UUID

/**
 * One sync run (M9), following docs/backend/openapi-v2.yaml → Conventions → Incremental sync:
 *
 * 1. push local deletions (`DELETE /v1/trips/{id}`);
 * 2. pull: without a stored token a full `GET /v1/trips` walk; with token S, walk `GET /v1/trips?since=S` to its
 *    last page (S' = its `nextSince`), then `GET /v1/trips/deletions?since=S`, then store S';
 * 3. push vehicles without a server id (`POST /v1/vehicles`, de-duplicated by `clientVehicleId`), then trip
 *    snapshots (`POST /v1/trips/batch`).
 *
 * `400 sync_window_expired` → a full walk, then by `reason`: `deployment_changed` re-creates vehicles the server
 * lost and re-uploads trips it didn't return (fresh `Idempotency-Key`s); `purged`, `invalid` and anything else
 * drop the uploaded-before trips it didn't return and re-upload nothing. Expiry on a cursor restarts the walk.
 * Retryable failures (`503`, `429`, network) propagate as [ApiError] for the worker to back off.
 */
class SyncEngine(
    private val api: BackendApi,
    private val store: SyncStore,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val maxRestarts: Int = 3,
) {
    data class Report(
        val pulled: Int = 0,
        val deletedByServer: Int = 0,
        val uploaded: Int = 0,
        val rejected: Int = 0,
        val recovery: SyncWindowReason? = null,
        val dropped: Int = 0,
        val vehiclesCreated: Int = 0,
    )

    private var report = Report()

    suspend fun run(): Report {
        report = Report()
        pushDeletions()
        pull()
        pushVehicles()
        pushTrips()
        return report
    }

    // --- pull ---

    private suspend fun pull() {
        val since = store.since()
        if (since == null) {
            val walk = fullWalk()
            store.storeSince(walk.nextSince)
            return
        }
        val reason = try {
            val next = walkTrips(since)
            walkDeletions(since)
            store.storeSince(next)
            return
        } catch (e: ApiError.Http) {
            if (!e.isSyncWindowExpired) throw e
            e.syncWindowReason
        }
        recover(reason)
    }

    private class FullWalk(val returned: Set<String>, val nextSince: String)

    /** Full `GET /v1/trips` without `since`. Expiry on a cursor discards the walk and starts it over. */
    private suspend fun fullWalk(): FullWalk {
        repeat(maxRestarts + 1) {
            val returned = mutableSetOf<String>()
            try {
                var cursor: String? = null
                while (true) {
                    val page = api.listTrips(since = null, cursor = cursor)
                    page.items.forEach { apply(it); returned += it.clientTripId }
                    cursor = page.nextCursor ?: return FullWalk(returned, page.nextSince ?: error("last page without nextSince"))
                }
            } catch (e: ApiError.Http) {
                if (!e.isSyncWindowExpired) throw e
            }
        }
        throw IllegalStateException("full walk kept expiring")
    }

    /** Incremental walk; returns S'. */
    private suspend fun walkTrips(since: String): String {
        var page = api.listTrips(since = since)
        while (true) {
            page.items.forEach { apply(it) }
            val cursor = page.nextCursor ?: return page.nextSince ?: error("last page without nextSince")
            page = api.listTrips(cursor = cursor)
        }
    }

    private suspend fun walkDeletions(since: String) {
        val local = store.trips().associateBy { it.clientTripId }
        var cursor: String? = null
        do {
            val page = api.listTripDeletions(since, cursor)
            page.items.forEach { d ->
                if (d.clientTripId in local) {
                    store.deleteTripLocally(d.clientTripId)
                    report = report.copy(deletedByServer = report.deletedByServer + 1)
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
    }

    /** Upsert by server id (or our own `clientTripId`); the newer `updatedAt` wins; local deletions win. */
    private suspend fun apply(remote: TripDto) {
        if (store.localDeletions().any { it.clientTripId == remote.clientTripId }) return
        val local = store.trips().firstOrNull { it.serverId == remote.id || it.clientTripId == remote.clientTripId }
        if (local?.serverUpdatedAt != null && !remote.updatedAt.isAfter(local.serverUpdatedAt)) return
        store.applyRemoteTrip(remote)
        report = report.copy(pulled = report.pulled + 1)
    }

    private suspend fun recover(first: SyncWindowReason) {
        var reason = first
        repeat(maxRestarts + 1) {
            report = report.copy(recovery = reason)
            try {
                val walk = fullWalk()
                if (reason == SyncWindowReason.DEPLOYMENT_CHANGED) recoverDeployment(walk) else dropUnreturned(walk.returned)
                store.storeSince(walk.nextSince)
                return
            } catch (e: ApiError.Http) {
                if (!e.isSyncWindowExpired) throw e
                // deployment_changed wins over the others; another one during recovery starts it over.
                if (e.syncWindowReason == SyncWindowReason.DEPLOYMENT_CHANGED) reason = SyncWindowReason.DEPLOYMENT_CHANGED
            }
        }
        throw IllegalStateException("sync recovery kept expiring")
    }

    /** `purged` / `invalid` / unknown: drop the uploaded-before trips the server didn't return. Never re-upload. */
    private suspend fun dropUnreturned(returned: Set<String>) {
        store.trips().filter { it.uploadedBefore && it.clientTripId !in returned }.forEach {
            store.deleteTripLocally(it.clientTripId)
            report = report.copy(dropped = report.dropped + 1)
        }
    }

    /** `deployment_changed`: re-create lost vehicles (remap), then re-upload the trips the server lost. */
    private suspend fun recoverDeployment(walk: FullWalk) {
        store.setPendingTripBatch(null) // re-uploads get fresh Idempotency-Keys
        val serverVehicles = buildSet {
            var cursor: String? = null
            do {
                val page = api.listVehicles(cursor)
                page.items.forEach { v -> v.clientVehicleId?.let { add(it to v.id) } }
                cursor = page.nextCursor
            } while (cursor != null)
        }.toMap()
        for (v in store.vehicles()) {
            val known = serverVehicles[v.clientVehicleId]
            if (known != null) {
                if (known != v.serverId) store.setVehicleServerId(v.clientVehicleId, known)
                continue
            }
            if (v.serverId == null && store.trips().none { it.clientVehicleId == v.clientVehicleId }) continue
            try {
                val created = api.createVehicle(v.create, newKey())
                store.setVehicleServerId(v.clientVehicleId, created.id)
                report = report.copy(vehiclesCreated = report.vehiclesCreated + 1)
            } catch (e: ApiError.Http) {
                if (e.code != ApiError.CODE_VEHICLE_DELETED) throw e
                store.deleteVehicle(v.clientVehicleId, keepClientTripIds = walk.returned)
            }
        }
        val deletedHere = store.localDeletions().map { it.clientTripId }.toSet()
        val lost = store.trips()
            .filter { !it.remoteOrigin && it.clientTripId !in walk.returned && it.clientTripId !in deletedHere }
            .map { it.clientTripId }.toSet()
        store.requeueUpload(lost)
    }

    // --- push ---

    private suspend fun pushDeletions() {
        for (d in store.localDeletions().filter { !it.pushed }) {
            d.serverId?.let { api.deleteTrip(it) }
            store.markLocalDeletionPushed(d.clientTripId)
        }
    }

    private suspend fun pushVehicles() {
        val withTrips = store.trips().filter { it.needsUpload }.mapNotNull { it.clientVehicleId }.toSet()
        for (v in store.vehicles().filter { it.serverId == null && !it.goneOnServer && it.clientVehicleId in withTrips }) {
            try {
                // A fresh key is fine: the server de-duplicates by clientVehicleId (200 replay).
                val created = api.createVehicle(v.create, newKey())
                store.setVehicleServerId(v.clientVehicleId, created.id)
                report = report.copy(vehiclesCreated = report.vehiclesCreated + 1)
            } catch (e: ApiError.Http) {
                if (e.code != ApiError.CODE_VEHICLE_DELETED) throw e
                store.markVehicleGone(v.clientVehicleId) // deleted on another device: don't retry
            }
        }
    }

    private suspend fun pushTrips() {
        val attempted = mutableSetOf<String>() // each trip at most once per run, whatever the server answers
        while (true) {
            val batch = store.pendingTripBatch() ?: nextBatch(attempted) ?: return
            attempted += batch.trips.map { it.clientTripId }
            val result = api.uploadTrips(TripBatchDto(batch.trips), batch.idempotencyKey)
            val sent = batch.trips.associateBy { it.clientTripId }
            for (item in result.results) {
                val trip = sent[item.clientId] ?: continue
                when {
                    item.accepted && item.id != null -> {
                        store.markUploaded(item.clientId, item.id!!, trip.snapshotAt)
                        report = report.copy(uploaded = report.uploaded + 1)
                    }
                    item.problem?.code == ApiError.CODE_TRIP_DELETED -> store.deleteTripLocally(item.clientId)
                    item.problem?.code == ApiError.CODE_VEHICLE_NOT_FOUND -> {
                        // The server lost or never had the vehicle: re-create it next run.
                        store.vehicles().firstOrNull { it.serverId == trip.vehicleId }?.let { store.setVehicleServerId(it.clientVehicleId, null) }
                    }
                    else -> {
                        store.markRejected(item.clientId, item.problem?.code)
                        report = report.copy(rejected = report.rejected + 1)
                    }
                }
            }
            store.setPendingTripBatch(null)
        }
    }

    private suspend fun nextBatch(attempted: Set<String>): PendingTripBatch? {
        val gone = store.vehicles().filter { it.goneOnServer }.map { it.clientVehicleId }.toSet()
        val uploads = store.trips()
            .filter { it.needsUpload && !it.remoteOrigin && it.clientVehicleId !in gone && it.clientTripId !in attempted }
            .mapNotNull { store.uploadOf(it.clientTripId) }
            .take(BackendApi.MAX_TRIPS_PER_BATCH)
        if (uploads.isEmpty()) return null
        return PendingTripBatch(newKey(), uploads).also { store.setPendingTripBatch(it) }
    }
}
