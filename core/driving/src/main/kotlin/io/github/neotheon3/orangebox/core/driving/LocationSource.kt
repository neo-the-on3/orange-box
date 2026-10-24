package io.github.neotheon3.orangebox.core.driving

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.neotheon3.orangebox.core.common.OrangeLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executor
import javax.inject.Inject

/** One position fix. */
data class GeoFix(val latitude: Double, val longitude: Double, val accuracyM: Float?, val timeMillis: Long)

/** Cold: the platform is only asked for fixes while the flow is collected (i.e. while recording). */
fun interface LocationSource {
    fun fixes(): Flow<GeoFix>
}

/**
 * Keyless, GMS-free location from the platform [LocationManager] (GPS, plus network when present).
 * Completes empty without a location permission. Works with emulator mock fixes (`adb emu geo fix`).
 */
class AndroidLocationSource @Inject constructor(@ApplicationContext private val context: Context) : LocationSource {
    // The permission is checked at runtime just below (and SecurityException is caught); the app declares it.
    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<GeoFix> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        val providers = manager?.allProviders.orEmpty().filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        if (manager == null || !granted || providers.isEmpty()) {
            OrangeLog.i(OrangeLog.Areas.TRIP, "location_off", "reason" to if (!granted) "no_permission" else "no_provider")
            close()
            return@callbackFlow
        }
        val listener = LocationListenerCompat { l ->
            trySend(GeoFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, l.time))
        }
        val direct = Executor { it.run() }
        val request = LocationRequestCompat.Builder(INTERVAL_MILLIS).setMinUpdateIntervalMillis(INTERVAL_MILLIS).build()
        try {
            providers.forEach { LocationManagerCompat.requestLocationUpdates(manager, it, request, direct, listener) }
            OrangeLog.i(OrangeLog.Areas.TRIP, "location_on", "providers" to providers.joinToString(","))
        } catch (e: SecurityException) {
            OrangeLog.w(OrangeLog.Areas.TRIP, "location_off", "reason" to "security_exception")
            close()
        }
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }

    private companion object { const val INTERVAL_MILLIS = 1_000L }
}
