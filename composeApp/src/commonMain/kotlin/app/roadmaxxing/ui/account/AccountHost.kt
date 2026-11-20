package app.roadmaxxing.ui.account

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.account_prompt_dismiss
import app.roadmaxxing.resources.account_prompt_primary
import app.roadmaxxing.resources.account_prompt_share_body
import app.roadmaxxing.resources.account_prompt_share_title
import app.roadmaxxing.resources.account_prompt_third_body
import app.roadmaxxing.resources.account_prompt_third_title
import app.roadmaxxing.shared.account.AccountPromptReason
import app.roadmaxxing.shared.account.AuthState
import app.roadmaxxing.ui.celebration.CelebrationViewModel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** The conditions must hold this long before the prompt appears, so it never flashes in between states. */
private const val SETTLE_MS = 900L

/**
 * Hosts the account prompt (K3) and the sign-in overlay above the shell. [canShowPrompt] is true only while the
 * plain map is on screen and no burn is playing; a pending milestone celebration also holds the prompt back so
 * the two never overlap. The sign-in overlay itself is shown whenever something asked to open it.
 */
@Composable
fun AccountHost(
    canShowPrompt: Boolean,
    viewModel: AccountViewModel = koinViewModel(),
    celebrationViewModel: CelebrationViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val celebration by celebrationViewModel.card.collectAsStateWithLifecycle()
    var settled by remember { mutableStateOf(false) }
    val clear = canShowPrompt && celebration == null
    LaunchedEffect(clear) {
        if (clear) {
            delay(SETTLE_MS)
            settled = true
        } else {
            settled = false
        }
    }
    if (state.auth is AuthState.SignedIn) return
    if (state.form.signInOpen) {
        SignInOverlay(form = state.form, onIntent = viewModel::onIntent)
        return
    }
    val reason = state.prompt
    if (reason != null && clear && settled) {
        AccountPromptDialog(reason, onIntent = viewModel::onIntent)
    }
}

@Composable
fun AccountPromptDialog(reason: AccountPromptReason, onIntent: (AccountIntent) -> Unit) {
    val title = when (reason) {
        AccountPromptReason.ThirdDrive -> Res.string.account_prompt_third_title
        AccountPromptReason.FirstShare -> Res.string.account_prompt_share_title
    }
    val body = when (reason) {
        AccountPromptReason.ThirdDrive -> Res.string.account_prompt_third_body
        AccountPromptReason.FirstShare -> Res.string.account_prompt_share_body
    }
    AlertDialog(
        onDismissRequest = { onIntent(AccountIntent.PromptDismiss) },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = { onIntent(AccountIntent.PromptAccept) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(stringResource(Res.string.account_prompt_primary)) }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(AccountIntent.PromptDismiss) }) { Text(stringResource(Res.string.account_prompt_dismiss)) }
        },
    )
}
