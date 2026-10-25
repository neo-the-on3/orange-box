package io.github.neotheon3.orangebox.core.diagnostics

import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.database.DtcHistoryRepository
import io.github.neotheon3.orangebox.core.database.DtcRecordEntity
import io.github.neotheon3.orangebox.core.database.VehicleRepository
import io.github.neotheon3.orangebox.core.obd.DtcKind
import io.github.neotheon3.orangebox.core.obd.DtcRead
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.VinDecoder
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** One complete diagnostics read. */
data class DiagnosticsReport(
    val readAtMillis: Long,
    /** Stored, pending and permanent codes (a code can appear in more than one list). */
    val codes: List<TroubleCode>,
    /** From PID 01 01; null when the car didn't answer it. */
    val milOn: Boolean?,
    val vin: String?,
    /** Lists the car did not answer readably (shown as "couldn't read" rather than "none"). */
    val unreadable: Set<DtcStatus> = emptySet(),
    /** Lists the car doesn't support at all, typically permanent codes (mode 0A) on older cars (#196). */
    val unsupported: Set<DtcStatus> = emptySet(),
) {
    /** Codes per group; codes that aren't well-formed SAE codes fall back to Powertrain. */
    val byGroup: Map<DtcGroup, List<TroubleCode>> = codes.groupBy { it.group ?: DtcGroup.POWERTRAIN }

    val faultedGroups: Set<DtcGroup> get() = byGroup.keys
    val faultedZones: Set<VehicleZone> get() = byGroup.keys.mapTo(mutableSetOf()) { it.zone }

    /** Something mode 04 would clear (stored or pending). Permanent codes alone don't offer Clear. */
    val hasClearableCodes: Boolean get() = codes.any { it.status != DtcStatus.PERMANENT }

    /** The drive-start prompt warns on any code or a lit MIL. */
    val hasTroubles: Boolean get() = codes.isNotEmpty() || milOn == true

    fun codesIn(group: DtcGroup): List<TroubleCode> = byGroup[group].orEmpty()
}

sealed interface ScanResult {
    data class Success(val report: DiagnosticsReport) : ScanResult
    data object NotConnected : ScanResult
    data class Failed(val reason: String) : ScanResult
}

sealed interface ClearResult {
    /** Mode 04 acknowledged; [report] is the read that followed (null if that read failed). */
    data class Cleared(val report: DiagnosticsReport?) : ClearResult
    data object NotConnected : ClearResult
    /** Blocked: the car is moving (docs/ui/LEGACY_OBD_UI.md §5.4). */
    data object WhileMoving : ClearResult
    /** The ECU refused (no `44` reply), e.g. with the engine running on some cars. */
    data object Rejected : ClearResult
    data class Failed(val reason: String) : ClearResult
}

/** Reads, explains and clears trouble codes over the live OBD session, and keeps the history (#51). */
interface DiagnosticsRepository {
    /** The last successful read, or null before the first one. */
    val latest: StateFlow<DiagnosticsReport?>
    val busy: StateFlow<Boolean>
    val history: Flow<List<DtcRecordEntity>>

    suspend fun scan(): ScanResult

    /** Sends mode 04 (unless moving), records the clear and reads again. */
    suspend fun clear(): ClearResult
}

@Singleton
class ObdDiagnosticsRepository @Inject constructor(
    private val connection: ObdConnection,
    private val dtcHistory: DtcHistoryRepository,
    private val vehicles: VehicleRepository,
    private val telemetry: TelemetrySource,
) : DiagnosticsRepository {
    var clock: () -> Long = System::currentTimeMillis

    private val _latest = MutableStateFlow<DiagnosticsReport?>(null)
    override val latest: StateFlow<DiagnosticsReport?> = _latest.asStateFlow()
    private val _busy = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = _busy.asStateFlow()
    override val history: Flow<List<DtcRecordEntity>> = dtcHistory.history

    private val mutex = Mutex()

    /**
     * DTC lists each vehicle (by id; null = no vehicle set up) answered as unsupported, so later scans in
     * this process skip them (#196). Not persisted: a fresh start probes once more.
     */
    private val unsupportedByVehicle = mutableMapOf<Long?, Set<DtcStatus>>()

    override suspend fun scan(): ScanResult = mutex.withLock { busy { scanLocked() } }

    private suspend fun scanLocked(): ScanResult {
        val vehicleId = runCatching { vehicles.activeVehicle.first()?.id }.getOrNull()
        val known = unsupportedByVehicle[vehicleId].orEmpty()
        val read = try {
            connection.withSession { s ->
                val monitor = s.readMonitorStatus()
                val results = DtcStatus.entries.associateWith { if (it in known) DtcRead.Unsupported else s.readDtcResult(it.kind) }
                val vin = (s.readVin() as? VinDecoder.Result.Valid)?.vin
                Triple(monitor, results, vin)
            }
        } catch (e: ObdError) {
            OrangeLog.w(AREA, "scan_failed", "error" to e.describe())
            return ScanResult.Failed(e.describe())
        } ?: return ScanResult.NotConnected
        val (monitor, results, vin) = read
        val lists: Map<DtcStatus, List<String>?> = results.mapValues { (it.value as? DtcRead.Codes)?.codes }
        if (results.values.none { it is DtcRead.Codes }) {
            OrangeLog.w(AREA, "scan_unreadable")
            return ScanResult.Failed("The car's trouble-code replies could not be read")
        }
        val unsupported = results.filterValues { it is DtcRead.Unsupported }.keys
        if (unsupported != known) unsupportedByVehicle[vehicleId] = unsupported
        val at = clock()
        val codes = lists.flatMap { (status, list) -> list.orEmpty().map { TroubleCode(it.uppercase(), status) } }
        val report = DiagnosticsReport(
            readAtMillis = at,
            codes = codes,
            milOn = monitor?.milOn,
            vin = vin,
            unreadable = results.filterValues { it is DtcRead.Unreadable }.keys,
            unsupported = unsupported,
        )
        val readable = lists.mapNotNull { (status, list) -> list?.let { status.wire to it } }.toMap()
        runCatching { dtcHistory.recordReading(readable, vehicles.activeVehicle.first()?.id, at) }
            .onFailure { OrangeLog.w(AREA, "history_write_failed", "error" to it.message) }
        _latest.value = report
        OrangeLog.i(
            AREA, "scan", "stored" to lists[DtcStatus.STORED]?.size, "pending" to lists[DtcStatus.PENDING]?.size,
            "permanent" to lists[DtcStatus.PERMANENT]?.size, "mil" to monitor?.milOn,
        )
        return ScanResult.Success(report)
    }

    override suspend fun clear(): ClearResult = mutex.withLock {
        busy {
            val speed = telemetry.latest.value?.instant?.speedKph
            if (speed != null && speed > 0.0) return@busy ClearResult.WhileMoving
            val ack = try {
                connection.withSession { it.clearDtcs() }
            } catch (e: ObdError) {
                OrangeLog.w(AREA, "clear_failed", "error" to e.describe())
                return@busy ClearResult.Failed(e.describe())
            } ?: return@busy ClearResult.NotConnected
            OrangeLog.i(AREA, "clear", "acknowledged" to ack)
            if (!ack) return@busy ClearResult.Rejected
            runCatching { dtcHistory.recordCleared(clock()) }
                .onFailure { OrangeLog.w(AREA, "history_write_failed", "error" to it.message) }
            ClearResult.Cleared((scanLocked() as? ScanResult.Success)?.report)
        }
    }

    private inline fun <T> busy(block: () -> T): T {
        _busy.value = true
        try {
            return block()
        } finally {
            _busy.value = false
        }
    }

    private val DtcStatus.kind: DtcKind
        get() = when (this) {
            DtcStatus.STORED -> DtcKind.STORED
            DtcStatus.PENDING -> DtcKind.PENDING
            DtcStatus.PERMANENT -> DtcKind.PERMANENT
        }

    private companion object {
        const val AREA = OrangeLog.Areas.OBD
    }
}

