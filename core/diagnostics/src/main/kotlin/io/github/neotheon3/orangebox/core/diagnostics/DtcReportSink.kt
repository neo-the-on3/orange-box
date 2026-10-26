package io.github.neotheon3.orangebox.core.diagnostics

/** A finished trouble-code read or an acknowledged clear, handed on for upload (M9, #67). */
data class DtcReading(
    val kind: Kind,
    val atMillis: Long,
    /** The active vehicle's local id; reads without one aren't uploaded (the endpoint is per vehicle). */
    val vehicleId: Long?,
    val milOn: Boolean?,
    val vin: String?,
    /** Null when that list couldn't be read or isn't supported (left out, rather than sent as "none"). */
    val stored: List<String>?,
    val pending: List<String>?,
    val permanent: List<String>?,
) {
    enum class Kind { READ, CLEARED }
}

/**
 * Receives each [DtcReading]. Bound by the app, which queues them only while sync is on (Fake, or Remote while
 * signed in); without a binding (tests, Off-only builds) readings go nowhere.
 */
fun interface DtcReportSink {
    suspend fun onReading(reading: DtcReading)
}
