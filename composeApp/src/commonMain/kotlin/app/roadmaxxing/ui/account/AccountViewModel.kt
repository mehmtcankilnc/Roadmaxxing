package app.roadmaxxing.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.shared.account.AccountPromptReason
import app.roadmaxxing.shared.account.AccountRepository
import app.roadmaxxing.shared.account.AuthError
import app.roadmaxxing.shared.account.AuthResult
import app.roadmaxxing.shared.account.AuthState
import app.roadmaxxing.shared.sync.AccountConflict
import app.roadmaxxing.shared.sync.ConflictChoice
import app.roadmaxxing.shared.sync.SyncManager
import app.roadmaxxing.shared.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SignIn, Create }

/** Where the two-step account delete flow is. */
enum class AccountDeleteStep { None, Explain, Confirm }

/** Local UI state of the sign-in screen and the delete flow (the auth state itself comes from the repository). */
data class AccountForm(
    val signInOpen: Boolean = false,
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: AuthError? = null,
    val resetSent: Boolean = false,
    val deleteStep: AccountDeleteStep = AccountDeleteStep.None,
    val alsoDeleteLocal: Boolean = false,
    val deleteError: AuthError? = null,
    /** True while the "clear this phone and download" confirm step of the account conflict dialog is up. */
    val conflictConfirmReplace: Boolean = false,
)

data class AccountUiState(
    val auth: AuthState = AuthState.Unavailable,
    val prompt: AccountPromptReason? = null,
    val form: AccountForm = AccountForm(),
    val sync: SyncStatus = SyncStatus.Disabled,
    val conflict: AccountConflict? = null,
)

sealed interface AccountIntent {
    data object OpenSignIn : AccountIntent
    data object CloseSignIn : AccountIntent
    data class SetMode(val mode: AuthMode) : AccountIntent
    data class SetEmail(val value: String) : AccountIntent
    data class SetPassword(val value: String) : AccountIntent
    data object Submit : AccountIntent
    data object Google : AccountIntent
    data object ForgotPassword : AccountIntent
    data object PromptAccept : AccountIntent
    data object PromptDismiss : AccountIntent
    data object SignOut : AccountIntent
    data object AskDelete : AccountIntent
    data object DeleteContinue : AccountIntent
    data class SetAlsoDeleteLocal(val value: Boolean) : AccountIntent
    data object CancelDelete : AccountIntent
    data object ConfirmDelete : AccountIntent
    data object SyncNow : AccountIntent
    data class ResolveConflict(val choice: ConflictChoice) : AccountIntent
    data class SetConflictConfirm(val open: Boolean) : AccountIntent
}

/** MVI view model over [AccountRepository]: sign-in form, the account prompt and the delete flow. */
class AccountViewModel(
    private val repository: AccountRepository,
    private val syncManager: SyncManager,
) : ViewModel() {

    private val form = MutableStateFlow(AccountForm())

    val state: StateFlow<AccountUiState> = combine(
        combine(repository.state, repository.prompt, form) { auth, prompt, f -> Triple(auth, prompt, f) },
        syncManager.status,
        syncManager.conflict,
    ) { (auth, prompt, f), sync, conflict ->
        AccountUiState(auth, prompt, f, sync, conflict)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AccountUiState(repository.state.value, repository.prompt.value, form.value, syncManager.status.value, syncManager.conflict.value),
    )

    fun onIntent(intent: AccountIntent) {
        when (intent) {
            AccountIntent.OpenSignIn -> form.update { AccountForm(signInOpen = true) }
            AccountIntent.CloseSignIn -> form.update { AccountForm() }
            is AccountIntent.SetMode -> form.update { it.copy(mode = intent.mode, error = null, resetSent = false) }
            is AccountIntent.SetEmail -> form.update { it.copy(email = intent.value, error = null, resetSent = false) }
            is AccountIntent.SetPassword -> form.update { it.copy(password = intent.value, error = null) }
            AccountIntent.Submit -> submit()
            AccountIntent.Google -> run { repository.signInWithGoogle() }
            AccountIntent.ForgotPassword -> forgotPassword()
            AccountIntent.PromptAccept -> {
                // The prompt is shown once; opening the sign-in screen counts as having seen it.
                repository.dismissPrompt()
                form.update { AccountForm(signInOpen = true) }
            }
            AccountIntent.PromptDismiss -> repository.dismissPrompt()
            AccountIntent.SignOut -> viewModelScope.launch { repository.signOut() }
            AccountIntent.AskDelete -> form.update { it.copy(deleteStep = AccountDeleteStep.Explain, alsoDeleteLocal = false, deleteError = null) }
            AccountIntent.DeleteContinue -> form.update { it.copy(deleteStep = AccountDeleteStep.Confirm) }
            is AccountIntent.SetAlsoDeleteLocal -> form.update { it.copy(alsoDeleteLocal = intent.value) }
            AccountIntent.CancelDelete -> form.update { it.copy(deleteStep = AccountDeleteStep.None, deleteError = null) }
            AccountIntent.ConfirmDelete -> confirmDelete()
            AccountIntent.SyncNow -> syncManager.syncNow()
            is AccountIntent.SetConflictConfirm -> form.update { it.copy(conflictConfirmReplace = intent.open) }
            is AccountIntent.ResolveConflict -> {
                form.update { it.copy(conflictConfirmReplace = false) }
                syncManager.resolveConflict(intent.choice)
            }
        }
    }

    private fun submit() {
        val f = form.value
        if (f.busy) return
        val email = f.email.trim()
        if (!looksLikeEmail(email)) {
            form.update { it.copy(error = AuthError.InvalidEmail) }
            return
        }
        run {
            if (f.mode == AuthMode.Create) repository.signUpWithEmail(email, f.password)
            else repository.signInWithEmail(email, f.password)
        }
    }

    private fun forgotPassword() {
        val f = form.value
        if (f.busy) return
        val email = f.email.trim()
        if (!looksLikeEmail(email)) {
            form.update { it.copy(error = AuthError.InvalidEmail, resetSent = false) }
            return
        }
        form.update { it.copy(busy = true, error = null, resetSent = false) }
        viewModelScope.launch {
            val result = repository.sendPasswordReset(email)
            form.update {
                when (result) {
                    AuthResult.Success -> it.copy(busy = false, resetSent = true)
                    AuthResult.Cancelled -> it.copy(busy = false)
                    is AuthResult.Failure -> it.copy(busy = false, error = result.error)
                }
            }
        }
    }

    /** Runs an auth call with the progress state; success closes the sign-in screen. */
    private fun run(call: suspend () -> AuthResult) {
        if (form.value.busy) return
        form.update { it.copy(busy = true, error = null, resetSent = false) }
        viewModelScope.launch {
            val result = call()
            form.update {
                when (result) {
                    AuthResult.Success -> AccountForm()
                    AuthResult.Cancelled -> it.copy(busy = false)
                    is AuthResult.Failure -> it.copy(busy = false, error = result.error)
                }
            }
        }
    }

    private fun confirmDelete() {
        val f = form.value
        if (f.busy) return
        form.update { it.copy(busy = true, deleteError = null) }
        viewModelScope.launch {
            val result = repository.deleteAccount(f.alsoDeleteLocal)
            form.update {
                when (result) {
                    AuthResult.Success -> AccountForm()
                    AuthResult.Cancelled -> it.copy(busy = false)
                    // Stay on the confirm step so the message is visible next to the retry.
                    is AuthResult.Failure -> it.copy(busy = false, deleteError = result.error)
                }
            }
        }
    }

    private fun looksLikeEmail(value: String): Boolean {
        val at = value.indexOf('@')
        return at > 0 && value.indexOf('.', at) > at + 1 && !value.endsWith('.') && ' ' !in value
    }
}
