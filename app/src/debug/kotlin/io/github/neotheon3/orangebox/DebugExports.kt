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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

    /** Writes `trips-<stamp>.json` (every trip with samples and events) and `trips-<stamp>.gpx` (located tracks). */
    suspend fun exportTrips(): List<File> = withContext(Dispatchers.IO) {
        val all = trips.trips.first().sortedBy { it.startedAtMillis }
        val stamp = stamp()
        val json = File(exportDir(), "trips-$stamp.json")
        val gpx = File(exportDir(), "trips-$stamp.gpx")
        json.bufferedWriter().use { out ->
            JsonWriter(out).use { w ->
                w.setIndent(" ")
                w.beginObject()
                w.name("format").value("orange-box-trips/1")
                w.name("exportedAt").value(iso(System.currentTimeMillis()))
                w.name("app").value(BuildConfig.VERSION_NAME)
                w.name("trips").beginArray()
                for (t in all) writeTrip(w, t, trips.samples(t.id), trips.events(t.id))
                w.endArray()
                w.endObject()
            }
        }
        gpx.bufferedWriter().use { out -> writeGpx(out, all) }
        OrangeLog.i(OrangeLog.Areas.APP, "debug_export_trips", "trips" to all.size, "file" to json.name)
        listOf(json, gpx)
    }

    /** Writes `connection-log-<stamp>.txt` from [ConnectionLog]. */
    suspend fun exportConnectionLog(): File = withContext(Dispatchers.IO) {
        val file = File(exportDir(), "connection-log-${stamp()}.txt")
        file.bufferedWriter().use { out ->
            out.write("# Orange Box connection log ${BuildConfig.VERSION_NAME}, exported ${iso(System.currentTimeMillis())}\n")
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
        w.name("startedAt").value(iso(t.startedAtMillis))
        w.name("endedAt").apply { t.endedAtMillis?.let { value(iso(it)) } ?: nullValue() }
        w.name("endReason").value(t.endReason)
        w.name("summary").beginObject()
        w.name("durationS").value(t.durationS).name("distanceKm").value(t.distanceKm).name("fuelLitres").value(t.fuelLitres)
        w.name("co2Grams").value(t.co2Grams).name("idleS").value(t.idleS).name("fuelCutS").value(t.fuelCutS)
        w.name("ecoS").value(t.ecoS).name("overspeedS").value(t.overspeedS).name("maxSpeedKph").value(t.maxSpeedKph)
        w.name("harshAccelCount").value(t.harshAccelCount.toLong()).name("harshBrakeCount").value(t.harshBrakeCount.toLong())
        w.name("overspeedCount").value(t.overspeedCount.toLong())
        w.endObject()
        w.name("events").beginArray()
        events.forEach { e ->
            w.beginObject()
            w.name("t").value(iso(e.timestampMillis)).name("type").value(e.type)
            num(w, "value", e.value); num(w, "lat", e.latitude); num(w, "lon", e.longitude)
            w.endObject()
        }
        w.endArray()
        // Compact rows: [time, speedKph, rpm, fuelFlowLph, coolantC, lat, lon, accuracyM]
        w.name("sampleColumns").beginArray()
        listOf("t", "speedKph", "rpm", "fuelFlowLph", "coolantC", "lat", "lon", "accuracyM").forEach { w.value(it) }
        w.endArray()
        w.name("samples").beginArray()
        samples.forEach { s ->
            w.beginArray()
            w.value(iso(s.timestampMillis))
            listOf(s.speedKph, s.rpm, s.fuelFlowLph, s.coolantC, s.latitude, s.longitude, s.accuracyM?.toDouble()).forEach { v ->
                if (v == null) w.nullValue() else w.value(v)
            }
            w.endArray()
        }
        w.endArray()
        w.endObject()
    }

    private suspend fun writeGpx(out: Writer, all: List<TripEntity>) {
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
        out.write("""<gpx version="1.1" creator="Orange Box ${BuildConfig.VERSION_NAME}" xmlns="http://www.topografix.com/GPX/1/1">""" + "\n")
        for (t in all) {
            val located = trips.samples(t.id).filter { it.latitude != null && it.longitude != null }
            if (located.isEmpty()) continue
            out.write("  <trk><name>Trip ${t.id} ${iso(t.startedAtMillis)}</name><trkseg>\n")
            located.forEach { s ->
                out.write("""    <trkpt lat="${s.latitude}" lon="${s.longitude}"><time>${iso(s.timestampMillis)}</time>""")
                s.speedKph?.let { out.write("<extensions><speed_kph>$it</speed_kph></extensions>") }
                out.write("</trkpt>\n")
            }
            out.write("  </trkseg></trk>\n")
        }
        out.write("</gpx>\n")
    }

    private fun num(w: JsonWriter, name: String, v: Double?) {
        w.name(name); if (v == null) w.nullValue() else w.value(v)
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugExportsEntryPoint {
    fun debugExports(): DebugExports
}
