package io.github.neotheon3.orangebox.sync

import io.github.neotheon3.orangebox.core.database.RoomSyncStore
import io.github.neotheon3.orangebox.core.diagnostics.DtcReading
import io.github.neotheon3.orangebox.core.diagnostics.DtcReportSink
import io.github.neotheon3.orangebox.core.network.DtcReportCreateDto
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queues DTC reads and clears for `POST /v1/vehicles/{vehicleId}/dtc-reports` (#67), only while sync is on
 * ([SyncEligibility]); in Off, or Remote signed out, nothing is kept. The next sync run uploads them.
 */
@Singleton
class AppDtcReportSink(
    private val eligibility: SyncEligibility,
    private val store: RoomSyncStore,
    private val newId: () -> String,
) : DtcReportSink {
    @Inject constructor(eligibility: SyncEligibility, store: RoomSyncStore) : this(eligibility, store, { UUID.randomUUID().toString() })

    override suspend fun onReading(reading: DtcReading) {
        val vehicleId = reading.vehicleId ?: return // the endpoint is per vehicle
        if (!eligibility.isOn()) return
        store.enqueueDtcReport(vehicleId, toDto(reading, newId()))
    }

    companion object {
        /**
         * Spec `DtcReportCreate`. Lists that couldn't be read stay out (absent ≠ none); codes that aren't SAE form
         * and a VIN that isn't a valid 17-character one are left out rather than failing the whole report (`422`).
         */
        fun toDto(r: DtcReading, clientReportId: String) = DtcReportCreateDto(
            clientReportId = clientReportId,
            kind = when (r.kind) {
                DtcReading.Kind.READ -> DtcReportCreateDto.KIND_READ
                DtcReading.Kind.CLEARED -> DtcReportCreateDto.KIND_CLEARED
            },
            readAt = Instant.ofEpochMilli(r.atMillis),
            // Null when unknown (a cleared report, or no PID 01 01 answer); never false for unknown (draft.9).
            mil = r.milOn,
            stored = r.stored?.let(::codes),
            pending = r.pending?.let(::codes),
            permanent = r.permanent?.let(::codes),
            vin = r.vin?.trim()?.uppercase()?.takeIf { DtcReportCreateDto.VIN.matches(it) },
        )

        private fun codes(list: List<String>) = list.map { it.trim().uppercase() }.filter { DtcReportCreateDto.CODE.matches(it) }.distinct()
    }
}
