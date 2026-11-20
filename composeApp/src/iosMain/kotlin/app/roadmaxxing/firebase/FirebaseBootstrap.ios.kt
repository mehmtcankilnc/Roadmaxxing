package app.roadmaxxing.firebase

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.initialize
import platform.Foundation.NSBundle
import platform.Foundation.NSDictionary
import platform.Foundation.dictionaryWithContentsOfFile

/**
 * The bundled GoogleService-Info.plist as a dictionary, or null. CI writes an empty placeholder plist when the secret
 * is missing, so presence alone does not mean a usable config.
 */
private fun bundledPlist(): Map<Any?, *>? {
    val path = NSBundle.mainBundle.pathForResource("GoogleService-Info", "plist") ?: return null
    return NSDictionary.dictionaryWithContentsOfFile(path)
}

private fun Map<Any?, *>.nonEmpty(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

internal actual fun initializePlatformFirebase(): Boolean {
    val plist = bundledPlist() ?: return false
    // Same keys FirebaseApp.configure() needs; an empty or partial plist keeps Firebase off.
    if (plist.nonEmpty("API_KEY") == null || plist.nonEmpty("GOOGLE_APP_ID") == null || plist.nonEmpty("PROJECT_ID") == null) {
        return false
    }
    // No options: the SDK reads GoogleService-Info.plist (FirebaseApp.configure()).
    Firebase.initialize(null)
    return true
}

internal actual fun platformGoogleClientId(): String? = bundledPlist()?.nonEmpty("CLIENT_ID")
