package app.roadmaxxing.auth

import app.roadmaxxing.firebase.FirebaseBootstrap
import app.roadmaxxing.shared.account.AccountUser
import app.roadmaxxing.shared.account.AuthBackend
import app.roadmaxxing.shared.account.AuthError
import app.roadmaxxing.shared.account.AuthProvider
import app.roadmaxxing.shared.account.AuthResult
import app.roadmaxxing.shared.account.AuthState
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.FirebaseUser
import dev.gitlive.firebase.auth.GoogleAuthProvider
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Firebase Auth implementation of [AuthBackend]: email/password and Google. */
class FirebaseAuthBackend(private val google: GoogleCredentialProvider) : AuthBackend {

    private val available: Boolean = FirebaseBootstrap.isAvailable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val unavailable = AuthResult.Failure(AuthError.Unknown)

    private val _state = MutableStateFlow<AuthState>(
        if (available) AuthState.SignedOut else AuthState.Unavailable,
    )
    override val state: StateFlow<AuthState> = _state

    init {
        if (available) {
            Firebase.auth.authStateChanged
                .onEach { _state.value = it.toAuthState() }
                .catch { _state.value = AuthState.SignedOut }
                .launchIn(scope)
        }
    }

    override suspend fun signUpWithEmail(email: String, password: String): AuthResult =
        guarded(signedInAs = SignedInAs.Email(email.trim())) {
            Firebase.auth.createUserWithEmailAndPassword(email.trim(), password)
        }

    override suspend fun signInWithEmail(email: String, password: String): AuthResult =
        guarded(signedInAs = SignedInAs.Email(email.trim())) {
            Firebase.auth.signInWithEmailAndPassword(email.trim(), password)
        }

    override suspend fun sendPasswordReset(email: String): AuthResult = guarded {
        Firebase.auth.sendPasswordResetEmail(email.trim())
    }

    override suspend fun signInWithGoogle(): AuthResult {
        if (!available) return unavailable
        return when (val picked = google.requestCredential()) {
            GoogleCredentialResult.Cancelled -> AuthResult.Cancelled
            is GoogleCredentialResult.Failure -> AuthResult.Failure(picked.error)
            is GoogleCredentialResult.Token -> guarded(signedInAs = SignedInAs.Anyone) {
                Firebase.auth.signInWithCredential(GoogleAuthProvider.credential(picked.idToken, picked.accessToken))
            }
        }
    }

    override suspend fun signOut() {
        if (!available) return
        try {
            Firebase.auth.signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Signing out locally cannot meaningfully fail; ignore.
        }
    }

    override suspend fun deleteAccount(): AuthResult {
        if (!available) return unavailable
        val user = Firebase.auth.currentUser ?: return AuthResult.Failure(AuthError.UserNotFound)
        return guarded(signedInAs = SignedInAs.Nobody) { user.delete() }
    }

    /** What the signed-in user must look like for a failed call to count as having actually succeeded. */
    private sealed interface SignedInAs {
        data class Email(val address: String) : SignedInAs
        data object Anyone : SignedInAs
        data object Nobody : SignedInAs
    }

    /**
     * Runs [block] without an artificial timeout (Firebase applies its own). On a slow network the SDK can report
     * a failure after the server already completed the request, so a failed call re-checks the actual session
     * ([signedInAs]) before reporting the failure: otherwise a created account would answer "failed", and a retry
     * "already exists".
     */
    private suspend fun guarded(signedInAs: SignedInAs? = null, block: suspend () -> Unit): AuthResult {
        if (!available) return unavailable
        return try {
            block()
            AuthResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val error = mapAuthFailure(t)
            if (signedInAs != null && error.isAmbiguous() && sessionMatches(signedInAs)) {
                AuthResult.Success
            } else {
                AuthResult.Failure(error)
            }
        }
    }

    /** Definitive answers from the server (wrong password, email taken, ...) are never second-guessed. */
    private fun AuthError.isAmbiguous(): Boolean = this == AuthError.Network || this == AuthError.Unknown

    private fun sessionMatches(expected: SignedInAs): Boolean {
        val current = try {
            Firebase.auth.currentUser
        } catch (_: Throwable) {
            return false
        }
        return when (expected) {
            SignedInAs.Nobody -> current == null
            SignedInAs.Anyone -> current != null
            is SignedInAs.Email -> current?.email.equals(expected.address, ignoreCase = true)
        }
    }
}

private fun FirebaseUser?.toAuthState(): AuthState {
    val user = this ?: return AuthState.SignedOut
    val providerIds = user.providerData.map { it.providerId }
    val provider = when {
        "google.com" in providerIds -> AuthProvider.Google
        "apple.com" in providerIds -> AuthProvider.Apple
        else -> AuthProvider.Email
    }
    return AuthState.SignedIn(
        AccountUser(uid = user.uid, email = user.email, displayName = user.displayName, provider = provider),
    )
}
