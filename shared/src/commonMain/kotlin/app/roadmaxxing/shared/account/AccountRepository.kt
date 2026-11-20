package app.roadmaxxing.shared.account

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.onboarding.AppMetaStore
import app.roadmaxxing.shared.settings.DataEraser
import app.roadmaxxing.shared.sync.NoopSyncRemote
import app.roadmaxxing.shared.sync.SyncRemote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class AccountPromptReason { ThirdDrive, FirstShare }

/**
 * Account state and the "create an account" prompt policy (K3). It only exposes state; it never blocks sharing.
 *
 * Guest data: signing in, signing up or signing out never deletes or rewrites local data (drives, roads, settings).
 * Syncing drives to the account is a later stage. Local data is only erased by [deleteAccount] with
 * `alsoDeleteLocal = true`.
 *
 * Prompt rules: [AccountPromptReason.ThirdDrive] is due once there are at least [THIRD_DRIVE_COUNT] drives with
 * verdict Drive; [AccountPromptReason.FirstShare] is due after the first [onShareAttempted]. Each reason is shown
 * at most once ([dismissPrompt] persists it in `app_meta`), never while signed in or when the backend is
 * unavailable. Only one reason is exposed at a time; an explicit share attempt wins over the third-drive prompt.
 */
class AccountRepository(
    private val backend: AuthBackend,
    private val store: DriveStore,
    private val appMeta: AppMetaStore,
    private val dataEraser: DataEraser,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val syncRemote: SyncRemote = NoopSyncRemote(),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val state: StateFlow<AuthState> get() = backend.state

    private val shown = MutableStateFlow(loadShown())
    private val sharePending = MutableStateFlow(false)

    /** Non-null when the prompt should be shown now. */
    val prompt: StateFlow<AccountPromptReason?> = combine(
        backend.state,
        store.observeDrives().map { drives -> drives.count { it.summary?.verdict == DriveVerdict.Drive } }
            .distinctUntilChanged(),
        sharePending,
        shown,
    ) { auth, driveCount, share, shownKeys ->
        when {
            auth !is AuthState.SignedOut -> null
            share && KEY_FIRST_SHARE !in shownKeys -> AccountPromptReason.FirstShare
            driveCount >= THIRD_DRIVE_COUNT && KEY_THIRD_DRIVE !in shownKeys -> AccountPromptReason.ThirdDrive
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Call when the user taps share. Makes [prompt] due the first time while signed out; does not block sharing. */
    fun onShareAttempted() {
        if (backend.state.value is AuthState.SignedOut && KEY_FIRST_SHARE !in shown.value) {
            sharePending.value = true
        }
    }

    /** Marks the current reason as shown; it is never offered again. */
    fun dismissPrompt() {
        val reason = prompt.value ?: return
        val key = when (reason) {
            AccountPromptReason.ThirdDrive -> KEY_THIRD_DRIVE
            AccountPromptReason.FirstShare -> KEY_FIRST_SHARE
        }
        appMeta.put(key, TRUE)
        if (reason == AccountPromptReason.FirstShare) sharePending.value = false
        shown.value = shown.value + key
    }

    suspend fun signUpWithEmail(email: String, password: String): AuthResult = backend.signUpWithEmail(email, password)

    suspend fun signInWithEmail(email: String, password: String): AuthResult = backend.signInWithEmail(email, password)

    suspend fun sendPasswordReset(email: String): AuthResult = backend.sendPasswordReset(email)

    suspend fun signInWithGoogle(): AuthResult = backend.signInWithGoogle()

    /** Signs out; local data stays untouched. */
    suspend fun signOut() = backend.signOut()

    /**
     * Deletes the account. While signed in, the user's cloud data is deleted FIRST via [SyncRemote.deleteAll]; if
     * that fails nothing else happens and the failure is returned ([AuthError.Network] for a failed or timed-out
     * cloud call so the user can retry, [AuthError.Unknown] when sync is unavailable), so the auth account never
     * disappears while cloud data remains. Then the Firebase account is deleted; if [alsoDeleteLocal] and the backend
     * succeeded, local data is erased via [DataEraser]. [AuthError.RequiresRecentLogin] is returned unchanged and
     * nothing local is touched (cloud data is already gone by then, which is fine: a retry deletes nothing twice).
     */
    suspend fun deleteAccount(alsoDeleteLocal: Boolean): AuthResult {
        val signedIn = backend.state.value as? AuthState.SignedIn
        if (signedIn != null) {
            try {
                syncRemote.deleteAll(signedIn.user.uid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                return AuthResult.Failure(AuthError.Unknown)
            } catch (_: Throwable) {
                return AuthResult.Failure(AuthError.Network)
            }
        }
        val result = backend.deleteAccount()
        if (result is AuthResult.Success && alsoDeleteLocal) {
            dataEraser.eraseAll()
            // The erase also cleared the persisted prompt flags.
            sharePending.value = false
            shown.value = loadShown()
        }
        return result
    }

    private fun loadShown(): Set<String> =
        listOf(KEY_THIRD_DRIVE, KEY_FIRST_SHARE).filter { appMeta.get(it) == TRUE }.toSet()

    companion object {
        const val THIRD_DRIVE_COUNT = 3
        private const val KEY_THIRD_DRIVE = "account_prompt_third_drive"
        private const val KEY_FIRST_SHARE = "account_prompt_first_share"
        private const val TRUE = "1"
    }
}
