package app.roadmaxxing.platform

import android.content.res.Resources

/**
 * Region of the device (system locale list), not of the per-app language: with the app language set to "tr" the
 * app's own default locale has no country, but the system locale still does.
 */
actual fun deviceRegionCode(): String? {
    val locales = Resources.getSystem().configuration.locales
    for (i in 0 until locales.size()) {
        val country = locales[i].country
        if (country.isNotBlank()) return country
    }
    return null
}
