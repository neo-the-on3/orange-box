package io.github.neotheon3.orangebox.core.driving

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.model.DashboardPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceCountryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tm = shadowOf(context.getSystemService(TelephonyManager::class.java))
    private val locale = Locale.getDefault()

    @After fun restore() = Locale.setDefault(locale)

    @Test fun `SIM country wins over network and locale`() {
        tm.setSimCountryIso("gb"); tm.setNetworkCountryIso("us"); Locale.setDefault(Locale.CANADA)
        assertEquals("GB", DeviceCountry(context).iso())
        assertEquals("999", DeviceCountry(context).defaultEmergencyNumber())
    }

    @Test fun `network country, then the locale`() {
        tm.setSimCountryIso(""); tm.setNetworkCountryIso("au")
        assertEquals("000", DeviceCountry(context).defaultEmergencyNumber())
        tm.setNetworkCountryIso("")
        Locale.setDefault(Locale.US)
        assertEquals("911", DeviceCountry(context).defaultEmergencyNumber())
        Locale.setDefault(Locale("hi", "IN"))
        assertEquals("112", DeviceCountry(context).defaultEmergencyNumber())
    }

    @Test fun `no country falls back to 112 and a user number wins`() {
        tm.setSimCountryIso(""); tm.setNetworkCountryIso(""); Locale.setDefault(Locale.ENGLISH)
        assertNull(DeviceCountry(context).iso())
        assertEquals("112", DeviceCountry(context).defaultEmergencyNumber())
        assertEquals("100", DeviceCountry(context).emergencyNumber(DashboardPreferences(emergencyNumber = "100")))
        assertEquals("112", DeviceCountry(context).emergencyNumber(DashboardPreferences()))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickLaunchAppsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pm = shadowOf(context.packageManager)
    private val launcher get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun app(pkg: String, label: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = pkg; name = "$pkg.Main"; nonLocalizedLabel = label
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
        nonLocalizedLabel = label
    }

    @Test fun `lists launchable apps by label without Orange Box itself`() {
        pm.addResolveInfoForIntent(launcher, app("com.example.zmusic", "Music"))
        pm.addResolveInfoForIntent(launcher, app("com.example.maps", "maps"))
        pm.addResolveInfoForIntent(launcher, app(context.packageName, "Orange Box"))
        assertEquals(listOf("maps", "Music"), QuickLaunchApps(context).list().map { it.label })
    }

    @Test fun `find returns null for an uninstalled app and launch reports it`() {
        assertNull(QuickLaunchApps(context).find("com.example.gone"))
        assertFalse(QuickLaunchApps(context).launch("com.example.gone"))
        pm.addResolveInfoForIntent(launcher.setPackage("com.example.maps"), app("com.example.maps", "Maps"))
        assertEquals("Maps", QuickLaunchApps(context).find("com.example.maps")?.label)
    }

    @Test fun `launch starts the app's launcher activity in a new task`() {
        val launch = Intent(Intent.ACTION_MAIN).setClassName("com.example.maps", "com.example.maps.Main")
        pm.addResolveInfoForIntent(launcher.setPackage("com.example.maps"), app("com.example.maps", "Maps"))
        pm.installPackage(android.content.pm.PackageInfo().apply {
            packageName = "com.example.maps"; applicationInfo = ApplicationInfo().apply { packageName = "com.example.maps" }
        })
        pm.addActivityIfNotPresent(launch.component!!)
        pm.addIntentFilterForActivity(
            launch.component!!,
            android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        assertTrue(QuickLaunchApps(context).launch("com.example.maps"))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("com.example.maps", started.component?.packageName ?: started.`package`)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
