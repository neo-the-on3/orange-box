package io.github.neotheon3.orangebox.alerts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.model.AlertKind
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlertOutputTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `every alert has its own bundled tone and a spoken text`() {
        for (kind in AlertKind.entries) {
            val name = context.resources.getResourceEntryName(AndroidAlertOutput.soundOf(kind))
            assertTrue(name, name.startsWith("alert_"))
            context.resources.openRawResourceFd(AndroidAlertOutput.soundOf(kind)).use { assertTrue(it.length > 1_000) }
            assertTrue(context.getString(AndroidAlertOutput.textOf(kind)).isNotBlank())
        }
    }

    @Test fun `playing tones and voice does not throw`() {
        val output = AndroidAlertOutput(context)
        for (kind in AlertKind.entries) output.play(kind, sound = true, voice = true)
    }
}
