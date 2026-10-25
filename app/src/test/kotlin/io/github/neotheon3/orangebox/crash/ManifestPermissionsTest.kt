package io.github.neotheon3.orangebox.crash

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #52/#55: the crash prompt opens the dialer / SMS app; the merged manifest must never ask to call or text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManifestPermissionsTest {
    private val requested: List<String> by lazy {
        val app = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
    }

    @Test fun `merged manifest has no CALL_PHONE or SEND_SMS`() {
        assertTrue("manifest was read: $requested", "android.permission.INTERNET" in requested)
        listOf(
            "android.permission.CALL_PHONE",
            "android.permission.SEND_SMS",
            "android.permission.READ_CONTACTS",
            "android.permission.CALL_PRIVILEGED",
            "android.permission.PROCESS_OUTGOING_CALLS",
        ).forEach { assertFalse("$it must not be requested", it in requested) }
    }
}
