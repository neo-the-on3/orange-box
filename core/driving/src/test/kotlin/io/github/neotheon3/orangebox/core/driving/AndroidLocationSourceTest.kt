package io.github.neotheon3.orangebox.core.driving

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class AndroidLocationSourceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(LocationManager::class.java)

    @Test fun `without a location permission the flow completes empty`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(AndroidLocationSource(app).fixes().toList().isEmpty())
    }

    @Test fun `emits platform fixes while collected and unregisters after`() = runTest(UnconfinedTestDispatcher()) {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val got = mutableListOf<GeoFix>()
        val job = launch { AndroidLocationSource(app).fixes().collect { got += it } }
        shadowOf(manager).simulateLocation(
            Location(LocationManager.GPS_PROVIDER).apply { latitude = 12.97; longitude = 77.59; accuracy = 4f; time = 1_000 },
        )
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(listOf(GeoFix(12.97, 77.59, 4f, 1_000)), got)
        job.cancel()
        shadowOf(manager).simulateLocation(Location(LocationManager.GPS_PROVIDER).apply { latitude = 1.0; longitude = 2.0 })
        assertEquals(1, got.size)
    }
}
