package app.roadmaxxing.platform

/** True when the OS offers a per-app language screen (Android 13+, iOS). */
expect val supportsPerAppLanguage: Boolean

/** Opens the OS screen where the user picks this app's language. No-op where unsupported. */
expect fun openLanguageSettings()
