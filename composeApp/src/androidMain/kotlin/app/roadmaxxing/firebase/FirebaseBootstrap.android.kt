package app.roadmaxxing.firebase

import android.content.Context
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.apps
import dev.gitlive.firebase.initialize

private var appContext: Context? = null

/** Android entry point: call once from `Application.onCreate`. Safe without a Firebase config (stays unavailable). */
fun FirebaseBootstrap.initWith(context: Context) {
    appContext = context.applicationContext
    init()
}

internal actual fun initializePlatformFirebase(): Boolean {
    val context = appContext ?: return false
    // Without google-services.json the generated values are empty: Firebase stays off.
    if (GeneratedFirebaseConfig.APPLICATION_ID.isEmpty() ||
        GeneratedFirebaseConfig.API_KEY.isEmpty() ||
        GeneratedFirebaseConfig.PROJECT_ID.isEmpty()
    ) {
        return false
    }
    if (Firebase.apps(context).isEmpty()) {
        Firebase.initialize(
            context,
            FirebaseOptions(
                applicationId = GeneratedFirebaseConfig.APPLICATION_ID,
                apiKey = GeneratedFirebaseConfig.API_KEY,
                projectId = GeneratedFirebaseConfig.PROJECT_ID,
                gcmSenderId = GeneratedFirebaseConfig.GCM_SENDER_ID.ifEmpty { null },
                storageBucket = GeneratedFirebaseConfig.STORAGE_BUCKET.ifEmpty { null },
            ),
        )
    }
    return true
}

internal actual fun platformGoogleClientId(): String? =
    GeneratedFirebaseConfig.GOOGLE_WEB_CLIENT_ID.ifEmpty { null }
