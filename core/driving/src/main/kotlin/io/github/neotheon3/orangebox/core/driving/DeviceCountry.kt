package io.github.neotheon3.orangebox.core.driving

import android.content.Context
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.neotheon3.orangebox.core.model.DashboardPreferences
import io.github.neotheon3.orangebox.core.model.EmergencyNumbers
import java.util.Locale
import javax.inject.Inject

/** The user's country for defaults: SIM country, then network country, then the locale. No permission needed. */
class DeviceCountry @Inject constructor(@ApplicationContext private val context: Context) {
    fun iso(): String? {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return listOf(
            runCatching { tm?.simCountryIso }.getOrNull(),
            runCatching { tm?.networkCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstOrNull { !it.isNullOrBlank() && it.length == 2 }?.uppercase()
    }

    fun defaultEmergencyNumber(): String = EmergencyNumbers.forCountry(iso())

    /** What the hazard prompt dials: the user's number, else the country default. */
    fun emergencyNumber(prefs: DashboardPreferences): String = prefs.emergencyNumber ?: defaultEmergencyNumber()

    /** The display units: the user's choice, else the country's defaults (miles in the US and UK). */
    fun units(prefs: io.github.neotheon3.orangebox.core.model.AppPreferences): io.github.neotheon3.orangebox.core.model.UnitPreferences =
        prefs.units ?: io.github.neotheon3.orangebox.core.model.UnitPreferences.forCountry(iso())

    /** The petrol blend for the fuel estimate: the user's choice, else the country's default (E20 in India). */
    fun petrolBlend(prefs: io.github.neotheon3.orangebox.core.model.AppPreferences): io.github.neotheon3.orangebox.core.model.PetrolBlend =
        prefs.petrolBlend ?: io.github.neotheon3.orangebox.core.model.PetrolBlend.forCountry(iso())
}
