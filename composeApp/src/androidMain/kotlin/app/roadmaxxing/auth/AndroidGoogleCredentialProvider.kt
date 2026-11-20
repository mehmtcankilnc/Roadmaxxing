package app.roadmaxxing.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.roadmaxxing.firebase.FirebaseBootstrap
import app.roadmaxxing.shared.account.AuthError
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException

/** Google account picker via Credential Manager (needs Play services and a resumed Activity). */
class AndroidGoogleCredentialProvider(
    private val context: Context,
    private val activities: CurrentActivityHolder,
) : GoogleCredentialProvider {

    override suspend fun requestCredential(): GoogleCredentialResult {
        val activity = activities.activity ?: return GoogleCredentialResult.Failure(AuthError.Unknown)
        val clientId = FirebaseBootstrap.googleWebClientId
        if (clientId.isNullOrBlank()) return GoogleCredentialResult.Failure(AuthError.Unknown)

        // The explicit "Sign in with Google" option is meant for a button tap: it always offers the account chooser,
        // while GetGoogleIdOption can report "no credentials" on a cold first call.
        val option = GetSignInWithGoogleOption.Builder(clientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val response = CredentialManager.create(context).getCredential(activity, request)
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleCredentialResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleCredentialResult.Failure(AuthError.Unknown)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: GetCredentialCancellationException) {
            GoogleCredentialResult.Cancelled
        } catch (_: NoCredentialException) {
            // No Google account on the device.
            GoogleCredentialResult.Failure(AuthError.Unknown)
        } catch (e: GetCredentialException) {
            GoogleCredentialResult.Failure(mapAuthFailure(e))
        } catch (_: GoogleIdTokenParsingException) {
            GoogleCredentialResult.Failure(AuthError.Unknown)
        }
    }
}
