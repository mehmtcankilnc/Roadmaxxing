package app.roadmaxxing.shared.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AuthProvider { Email, Google, Apple }

data class AccountUser(val uid: String, val email: String?, val displayName: String?, val provider: AuthProvider)

sealed interface AuthState {
    /** Firebase is not configured on this build. */
    data object Unavailable : AuthState

    data object SignedOut : AuthState

    data class SignedIn(val user: AccountUser) : AuthState
}

enum class AuthError {
    InvalidCredentials,
    UserNotFound,
    EmailInUse,
    WeakPassword,
    InvalidEmail,
    Network,
    TooManyRequests,
    RequiresRecentLogin,
    Unknown,
}

sealed interface AuthResult {
    data object Success : AuthResult

    /** The user backed out of the provider's UI (for example closed the Google account picker). */
    data object Cancelled : AuthResult

    data class Failure(val error: AuthError) : AuthResult
}

/** Implemented in composeApp with Firebase (M3). `shared` itself has no Firebase dependency. */
interface AuthBackend {
    val state: StateFlow<AuthState>
    suspend fun signUpWithEmail(email: String, password: String): AuthResult
    suspend fun signInWithEmail(email: String, password: String): AuthResult
    suspend fun sendPasswordReset(email: String): AuthResult
    suspend fun signInWithGoogle(): AuthResult
    suspend fun signOut()
    suspend fun deleteAccount(): AuthResult
}

/** Used when no real backend is registered: [state] is [AuthState.Unavailable] and every call fails. */
class NoopAuthBackend : AuthBackend {
    override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.Unavailable)
    private val failure = AuthResult.Failure(AuthError.Unknown)

    override suspend fun signUpWithEmail(email: String, password: String): AuthResult = failure
    override suspend fun signInWithEmail(email: String, password: String): AuthResult = failure
    override suspend fun sendPasswordReset(email: String): AuthResult = failure
    override suspend fun signInWithGoogle(): AuthResult = failure
    override suspend fun signOut() {}
    override suspend fun deleteAccount(): AuthResult = failure
}
