package io.github.neotheon3.orangebox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.JsonWriter
import androidx.core.content.FileProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.core.common.ConnectionLog
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.TripEventEntity
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.database.TripSampleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-only exports for real-device beta testing. Files go to the app-specific external Downloads dir
 * (`Android/data/<pkg>/files/Download/orange-box-exports/`, no storage permission) and are offered through
 * the share sheet. Read-only on the database; nothing here runs on the driving or recording paths.
 */
@Singleton
class DebugExports @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trips: TripRepository,
) {
    fun exportDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "exports"), "orange-box-exports")
            .apply { mkdirs() }

    /** How far [exportTrips] has got. */
    data class Progress(val done: Int, val total: Int)

    /**
     * Writes `trips-<stamp>.json` (every trip with samples and events) and `trips-<stamp>.gpx` (located tracks)
     * in one streaming pass on [Dispatchers.IO] (#166): trips are read [BATCH] at a time with one query each for
     * samples and events, and each batch is written to both files before the next is read, so memory stays
     * bounded by a batch. [onProgress] gets (trips written, total) after each batch. Cancellable between
     * batches; a cancelled or failed export deletes its partial files.
     */
    suspend fun exportTrips(onProgress: (Progress) -> Unit = {}): List<File> = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val all = trips.trips.first().sortedBy { it.startedAtMillis }
        val stamp = stamp()
        val json = File(exportDir(), "trips-$stamp.json")
        val gpx = File(exportDir(), "trips-$stamp.gpx")
        var samplesWritten = 0L
        try {
            json.bufferedWriter(bufferSize = IO_BUFFER).use { jsonOut ->
                gpx.bufferedWriter(bufferSize = IO_BUFFER).use { gpxOut ->
                    JsonWriter(jsonOut).use { w ->
                        w.beginObject()
                        w.name("format").value("orange-box-trips/1")
                        w.name("exportedAt").value(IsoUtc.format(System.currentTimeMillis()))
                        w.name("app").value(BuildConfig.VERSION_NAME)
                        w.name("trips").beginArray()
                        gpxHeader(gpxOut)
                        onProgress(Progress(0, all.size))
                        var done = 0
                        for (batch in all.chunked(BATCH)) {
                            ensureActive()
                            val ids = batch.map { it.id }
                            val samples = trips.samplesOf(ids)
                            val events = trips.eventsOf(ids)
                            for (t in batch) {
                                val s = samples[t.id].orEmpty()
                                writeTrip(w, t, s, events[t.id].orEmpty())
                                writeGpxTrack(gpxOut, t, s)
                                samplesWritten += s.size
                            }
                            done += batch.size
                            onProgress(Progress(done, all.size))
                        }
                        w.endArray()
                        w.endObject()
                        gpxOut.write("</gpx>\n")
                    }
                }
            }
        } catch (e: Throwable) {
            json.delete(); gpx.delete()
            throw e
        }
        OrangeLog.i(
            OrangeLog.Areas.APP, "debug_export_trips", "trips" to all.size, "samples" to samplesWritten,
            "ms" to (System.nanoTime() - started) / 1_000_000, "file" to json.name,
        )
        listOf(json, gpx)
    }

    /** Writes `connection-log-<stamp>.txt` from [ConnectionLog]. */
    suspend fun exportConnectionLog(): File = withContext(Dispatchers.IO) {
        val file = File(exportDir(), "connection-log-${stamp()}.txt")
        file.bufferedWriter().use { out ->
            out.write("# Orange Box connection log ${BuildConfig.VERSION_NAME}, exported ${IsoUtc.format(System.currentTimeMillis())}\n")
            out.write("# Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
            out.write("# Debug build: contains raw ELM327 traffic incl. VIN replies and the dongle address.\n")
            out.write("# Kinds: CONNECT/OPEN/CLOSE transport, TX/RX raw lines (RX with ms since TX), ERR transport errors, LOG app events (state, retries).\n")
            ConnectionLog.writeTo(out)
        }
        OrangeLog.i(OrangeLog.Areas.APP, "debug_export_connection_log", "lines" to ConnectionLog.snapshot().size, "file" to file.name)
        file
    }

    fun shareIntent(files: List<File>, title: String): Intent {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(context, "${context.packageName}.debugexports", it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = if (files.all { it.name.endsWith(".txt") }) "text/plain" else "application/octet-stream"
        send.putExtra(Intent.EXTRA_SUBJECT, title)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun writeTrip(w: JsonWriter, t: TripEntity, samples: List<TripSampleEntity>, events: List<TripEventEntity>) {
        w.beginObject()
        w.name("id").value(t.id)
        w.name("vehicleId").apply { if (t.vehicleId == null) nullValue() else value(t.vehicleId!!) }
        w.name("startedAt").value(IsoUtc.format(t.startedAtMillis))
        w.name("endedAt").apply { t.endedAtMillis?.let { value(IsoUtc.format(it)) } ?: nullValue() }
        w.name("endReason").value(t.endReason)
        w.name("summary").beginObject()
        w.name("durationS").value(t.durationS).name("distanceKm").value(t.distanceKm).name("fuelLitres").value(t.fuelLitres)
        w.name("co2Grams").value(t.co2Grams).name("idleS").value(t.idleS).name("fuelCutS").value(t.fuelCutS)
        w.name("ecoS").value(t.ecoS).name("overspeedS").value(t.overspeedS).name("maxSpeedKph").value(t.maxSpeedKph)
        w.name("harshAccelCount").value(t.harshAccelCount.toLong()).name("harshBrakeCount").value(t.harshBrakeCount.toLong())
        w.name("overspeedCount").value(t.overspeedCount.toLong())
        w.endObject()
        w.name("events").beginArray()
        for (e in events) {
            w.beginObject()
            w.name("t").value(IsoUtc.format(e.timestampMillis)).name("type").value(e.type)
            num(w, "value", e.value); num(w, "durationS", e.durationS); num(w, "lat", e.latitude); num(w, "lon", e.longitude)
            w.endObject()
        }
        w.endArray()
        // Compact rows: [time, speedKph, rpm, fuelFlowLph, coolantC, lat, lon, accuracyM]
        w.name("sampleColumns").beginArray()
        for (c in SAMPLE_COLUMNS) w.value(c)
        w.endArray()
        w.name("samples").beginArray()
        for (s in samples) {
            w.beginArray()
            w.value(IsoUtc.format(s.timestampMillis))
            value(w, s.speedKph); value(w, s.rpm); value(w, s.fuelFlowLph); value(w, s.coolantC)
            value(w, s.latitude); value(w, s.longitude); value(w, s.accuracyM?.toDouble())
            w.endArray()
        }
        w.endArray()
        w.endObject()
    }

    private fun gpxHeader(out: Writer) {
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
        out.write("""<gpx version="1.1" creator="Orange Box ${BuildConfig.VERSION_NAME}" xmlns="http://www.topografix.com/GPX/1/1">""" + "\n")
    }

    /** One `<trk>` for [t]'s located samples; nothing when it has none. */
    private fun writeGpxTrack(out: Writer, t: TripEntity, samples: List<TripSampleEntity>) {
        var opened = false
        for (s in samples) {
            val lat = s.latitude ?: continue
            val lon = s.longitude ?: continue
            if (!opened) {
                out.write("  <trk><name>Trip "); out.write(t.id.toString()); out.write(" "); out.write(IsoUtc.format(t.startedAtMillis))
                out.write("</name><trkseg>\n")
                opened = true
            }
            out.write("    <trkpt lat=\""); out.write(lat.toString()); out.write("\" lon=\""); out.write(lon.toString())
            out.write("\"><time>"); out.write(IsoUtc.format(s.timestampMillis)); out.write("</time>")
            s.speedKph?.let { out.write("<extensions><speed_kph>"); out.write(it.toString()); out.write("</speed_kph></extensions>") }
            out.write("</trkpt>\n")
        }
        if (opened) out.write("  </trkseg></trk>\n")
    }

    private fun value(w: JsonWriter, v: Double?) {
        if (v == null) w.nullValue() else w.value(v)
    }

    private fun num(w: JsonWriter, name: String, v: Double?) {
        w.name(name); if (v == null) w.nullValue() else w.value(v)
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    companion object {
        /** Trips per database read: two queries per batch, and only one batch of samples in memory. */
        const val BATCH = 8
        private const val IO_BUFFER = 64 * 1024
        private val SAMPLE_COLUMNS = arrayOf("t", "speedKph", "rpm", "fuelFlowLph", "coolantC", "lat", "lon", "accuracyM")
    }
}

/**
 * `yyyy-MM-ddTHH:mm:ss.SSSZ` in UTC without a formatter object: a [SimpleDateFormat] per timestamp made the
 * export spend most of its time building formatters (#166).
 */
internal object IsoUtc {
    fun format(millis: Long): String {
        val days = Math.floorDiv(millis, 86_400_000L)
        val ms = Math.floorMod(millis, 86_400_000L).toInt()
        // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = (z - era * 146_097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        val sb = StringBuilder(24)
        pad(sb, y, 4); sb.append('-'); pad(sb, m.toLong(), 2); sb.append('-'); pad(sb, d.toLong(), 2); sb.append('T')
        pad(sb, (ms / 3_600_000).toLong(), 2); sb.append(':'); pad(sb, (ms / 60_000 % 60).toLong(), 2); sb.append(':')
        pad(sb, (ms / 1000 % 60).toLong(), 2); sb.append('.'); pad(sb, (ms % 1000).toLong(), 3); sb.append('Z')
        return sb.toString()
    }

    private fun pad(sb: StringBuilder, v: Long, width: Int) {
        val s = v.toString()
        repeat(width - s.length) { sb.append('0') }
        sb.append(s)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugExportsEntryPoint {
    fun debugExports(): DebugExports
}
