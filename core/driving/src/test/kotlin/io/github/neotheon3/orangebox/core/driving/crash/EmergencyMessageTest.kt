package io.github.neotheon3.orangebox.core.driving.crash

import android.content.Intent
import io.github.neotheon3.orangebox.core.driving.GeoFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmergencyMessageTest {
    private val fix = GeoFix(12.971598, 77.594566, 8f, 1_761_370_200_000) // 2025-10-25 05:30 UTC
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")

    @Test fun `location link is a keyless OpenStreetMap URL with 5 decimals`() {
        assertEquals(
            "https://www.openstreetmap.org/?mlat=12.97160&mlon=77.59457#map=17/12.97160/77.59457",
            EmergencyMessage.locationLink(fix),
        )
        assertFalse(EmergencyMessage.locationLink(fix).contains("google"))
    }

    @Test fun `message carries the intro, fix time and link`() {
        val text = EmergencyMessage.compose("I may have been in a car accident.", fix, "Last known location (%1\$s): %2\$s", "No location.", ist)
        assertEquals(
            "I may have been in a car accident. Last known location (11:00): " +
                "https://www.openstreetmap.org/?mlat=12.97160&mlon=77.59457#map=17/12.97160/77.59457",
            text,
        )
    }

    @Test fun `no fix says so`() {
        assertEquals("Help. No location.", EmergencyMessage.compose("Help.", null, "%1\$s %2\$s", "No location.", ist))
    }

    @Test fun `dial intent pre-fills the number and never calls`() {
        val i = EmergencyIntents.dial(" 112 ")
        assertEquals(Intent.ACTION_DIAL, i.action)
        assertEquals("tel", i.data!!.scheme)
        assertEquals("112", i.data!!.schemeSpecificPart)
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `sms intent pre-fills recipient and body`() {
        val i = EmergencyIntents.sms("+91 98450 12345", "Body text")
        assertEquals(Intent.ACTION_SENDTO, i.action)
        assertEquals("smsto", i.data!!.scheme)
        assertEquals("+91 98450 12345", i.data!!.schemeSpecificPart)
        assertEquals("Body text", i.getStringExtra(EmergencyIntents.EXTRA_SMS_BODY))
    }

    @Test fun `sms intent without a contact leaves the recipient empty`() {
        val i = EmergencyIntents.sms(null, "Body")
        assertEquals("smsto:", i.dataString)
        assertNull(i.data!!.schemeSpecificPart.takeIf { it.isNotEmpty() })
    }
}
