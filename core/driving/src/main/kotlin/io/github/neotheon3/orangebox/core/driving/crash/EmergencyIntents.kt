package io.github.neotheon3.orangebox.core.driving.crash

import android.content.Intent
import android.net.Uri
import io.github.neotheon3.orangebox.core.driving.GeoFix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The only ways the app reaches out in an emergency (#55): the phone's dialer with the number typed in
 * ([dial], `ACTION_DIAL`) and its SMS app with the recipient and text filled in ([sms], `ACTION_SENDTO
 * smsto:`). The user presses call / send; the app holds no CALL_PHONE or SEND_SMS permission.
 */
object EmergencyIntents {
    const val EXTRA_SMS_BODY = "sms_body"

    fun dial(number: String): Intent =
        Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number.trim(), null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** [number] may be null (no crash contact yet): the SMS app then asks for the recipient. */
    fun sms(number: String?, body: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number?.trim().orEmpty())))
            .putExtra(EXTRA_SMS_BODY, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** The prefilled SMS text. Pure, so it can be tested without Android. */
object EmergencyMessage {
    /** A keyless OpenStreetMap link to [fix] (no Google services), 5 decimals ≈ 1 m. */
    fun locationLink(fix: GeoFix): String {
        val lat = String.format(Locale.ROOT, "%.5f", fix.latitude)
        val lon = String.format(Locale.ROOT, "%.5f", fix.longitude)
        return "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
    }

    /**
     * [intro] (e.g. "I may have been in a car accident."), then the last location, or [noLocation] when
     * there is no fix. [locationLabel] formats as "Last known location (%1$s): %2$s" with the fix's local
     * time (so a stale fix reads as such) and the link.
     */
    fun compose(
        intro: String,
        fix: GeoFix?,
        locationLabel: String,
        noLocation: String,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        if (fix == null) return "$intro $noLocation"
        val time = SimpleDateFormat("HH:mm", Locale.ROOT).apply { this.timeZone = timeZone }.format(Date(fix.timeMillis))
        return "$intro " + String.format(Locale.ROOT, locationLabel, time, locationLink(fix))
    }
}
