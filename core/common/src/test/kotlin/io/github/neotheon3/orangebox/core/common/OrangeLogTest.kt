package io.github.neotheon3.orangebox.core.common

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class OrangeLogTest {
    private val lines = mutableListOf<String>()

    @After fun tearDown() {
        OrangeLog.sink = OrangeLog.Sink { _, _, _, _ -> }
        OrangeLog.allowSensitive = false
    }

    @Test fun `structured message and tag prefix`() {
        OrangeLog.sink = OrangeLog.Sink { level, tag, msg, _ -> lines += "$level $tag $msg" }
        OrangeLog.i(OrangeLog.Areas.OBD, "state", "from" to "Idle", "to" to "Connecting")
        assertEquals(listOf("INFO OrangeBox/OBD state from=Idle to=Connecting"), lines)
    }

    @Test fun `sensitive values are redacted unless allowed`() {
        OrangeLog.sink = OrangeLog.Sink { _, _, msg, _ -> lines += msg }
        OrangeLog.i("OBD", "vin", "vin" to OrangeLog.sensitive("1M8GDM9AXKP042788"))
        OrangeLog.allowSensitive = true
        OrangeLog.i("OBD", "vin", "vin" to OrangeLog.sensitive("1M8GDM9AXKP042788"))
        assertEquals(listOf("vin vin=<redacted>", "vin vin=1M8GDM9AXKP042788"), lines)
    }
}
