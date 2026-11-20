package app.roadmaxxing.firebase

/**
 * Single entry point for Firebase start-up. Firebase is optional: without a config file (android: git-ignored
 * google-services.json at build time; iOS: a real GoogleService-Info.plist in the bundle) [isAvailable] stays false
 * and the app runs fully local. Callers must check [isAvailable] before touching any Firebase API.
 *
 * Android must call `FirebaseBootstrap.initWith(context)` (androidMain extension) once from `Application.onCreate`;
 * iOS calls [init] from its Kotlin entry point.
 */
object FirebaseBootstrap {
    private var initialized = false
    private var available = false

    /** True once Firebase was initialised from a real config. False before [init] or when no config exists. */
    val isAvailable: Boolean get() = available

    /**
     * Client id for Google sign-in, or null when none is configured.
     * Android: the Web OAuth client id (`oauth_client` with `client_type` 3 in google-services.json), used as the
     * Credential Manager server client id. iOS: `CLIENT_ID` from GoogleService-Info.plist.
     */
    val googleWebClientId: String? get() = platformGoogleClientId()

    /** Idempotent. Never throws: a failed initialisation leaves [isAvailable] false. */
    fun init() {
        if (initialized) return
        initialized = true
        available = runCatching { initializePlatformFirebase() }.getOrDefault(false)
    }
}

/** Initialises the platform's Firebase app from the available config. Returns true on success. */
internal expect fun initializePlatformFirebase(): Boolean

/** Platform-specific client id for Google sign-in, or null. */
internal expect fun platformGoogleClientId(): String?
