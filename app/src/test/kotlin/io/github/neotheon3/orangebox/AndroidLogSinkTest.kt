package io.github.neotheon3.orangebox

import io.github.neotheon3.orangebox.core.common.OrangeLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidLogSinkTest {
    @After fun tearDown() = AndroidLogSink.install(debug = true)

    private fun ours() = ShadowLog.getLogs().filter { it.tag.startsWith("OrangeBox/") }

    @Test fun `release keeps warnings only and redacts sensitive values`() {
        ShadowLog.clear()
        AndroidLogSink.install(debug = false)
        OrangeLog.i("OBD", "state", "to" to "Connected")
        OrangeLog.w("OBD", "vin_invalid", "vin" to OrangeLog.sensitive("1M8GDM9AXKP042788"))
        val logs = ours()
        assertEquals(1, logs.size)
        assertEquals("OrangeBox/OBD", logs[0].tag)
        assertEquals("vin_invalid vin=<redacted>", logs[0].msg)
    }

    @Test fun `debug logs info with values`() {
        ShadowLog.clear()
        AndroidLogSink.install(debug = true)
        OrangeLog.i("BT", "acl_connected", "device" to OrangeLog.sensitive("AA:BB"))
        assertTrue(ours().any { it.msg == "acl_connected device=AA:BB" && it.type == android.util.Log.INFO })
    }
}
