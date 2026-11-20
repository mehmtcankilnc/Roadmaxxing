package app.roadmaxxing.auth

import app.roadmaxxing.shared.account.AuthError

/** Outcome of the platform's Google account picker. */
sealed interface GoogleCredentialResult {
    /** [idToken] is a Google ID token; [accessToken] is optional. */
    data class Token(val idToken: String, val accessToken: String? = null) : GoogleCredentialResult

    data object Cancelled : GoogleCredentialResult

    data class Failure(val error: AuthError) : GoogleCredentialResult
}

/**
 * Shows the platform Google account picker and returns tokens. The Firebase sign-in itself happens in
 * [FirebaseAuthBackend] so both platforms share it.
 */
interface GoogleCredentialProvider {
    suspend fun requestCredential(): GoogleCredentialResult
}
