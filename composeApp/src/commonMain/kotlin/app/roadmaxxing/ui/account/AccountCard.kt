package app.roadmaxxing.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.account_card_benefit
import app.roadmaxxing.resources.account_card_title
import app.roadmaxxing.resources.account_cancel
import app.roadmaxxing.resources.account_delete
import app.roadmaxxing.resources.account_delete_also_local
import app.roadmaxxing.resources.account_delete_confirm_button
import app.roadmaxxing.resources.account_delete_confirm_local
import app.roadmaxxing.resources.account_delete_confirm_online
import app.roadmaxxing.resources.account_delete_confirm_title
import app.roadmaxxing.resources.account_delete_continue
import app.roadmaxxing.resources.account_delete_explain
import app.roadmaxxing.resources.account_delete_title
import app.roadmaxxing.resources.account_provider_apple
import app.roadmaxxing.resources.account_provider_email
import app.roadmaxxing.resources.account_provider_google
import app.roadmaxxing.resources.account_provider_line
import app.roadmaxxing.resources.account_sign_in
import app.roadmaxxing.resources.account_sign_out
import app.roadmaxxing.resources.account_signed_in_as
import app.roadmaxxing.resources.account_unavailable_debug
import app.roadmaxxing.shared.account.AccountUser
import app.roadmaxxing.shared.account.AuthProvider
import app.roadmaxxing.shared.account.AuthState
import app.roadmaxxing.ui.components.InfoCard
import app.roadmaxxing.ui.components.SecondaryText
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Account card at the top of the Profile tab. Hidden when accounts are not configured (a small note shows in
 * debug builds only).
 */
@Composable
fun AccountCard(isDebugBuild: Boolean, viewModel: AccountViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountCardContent(state, isDebugBuild, viewModel::onIntent)
}

@Composable
fun AccountCardContent(state: AccountUiState, isDebugBuild: Boolean, onIntent: (AccountIntent) -> Unit) {
    when (val auth = state.auth) {
        AuthState.Unavailable -> if (isDebugBuild) SecondaryText(stringResource(Res.string.account_unavailable_debug))
        AuthState.SignedOut -> InfoCard(stringResource(Res.string.account_card_title)) {
            SecondaryText(stringResource(Res.string.account_card_benefit))
            Button(
                onClick = { onIntent(AccountIntent.OpenSignIn) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(stringResource(Res.string.account_sign_in)) }
        }
        is AuthState.SignedIn -> {
            InfoCard(stringResource(Res.string.account_card_title)) {
                SignedInBody(auth.user, onIntent)
            }
            DeleteDialogs(state.form, onIntent)
        }
    }
}

@Composable
private fun SignedInBody(user: AccountUser, onIntent: (AccountIntent) -> Unit) {
    val identity = user.email ?: user.displayName ?: ""
    Text(
        text = stringResource(Res.string.account_signed_in_as, identity),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val provider = stringResource(
        when (user.provider) {
            AuthProvider.Google -> Res.string.account_provider_google
            AuthProvider.Email -> Res.string.account_provider_email
            AuthProvider.Apple -> Res.string.account_provider_apple
        },
    )
    SecondaryText(stringResource(Res.string.account_provider_line, provider))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { onIntent(AccountIntent.SignOut) }) { Text(stringResource(Res.string.account_sign_out)) }
        TextButton(onClick = { onIntent(AccountIntent.AskDelete) }) {
            Text(stringResource(Res.string.account_delete), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Two-step delete: explain what goes (with the local-data checkbox), then a final confirmation. */
@Composable
private fun DeleteDialogs(form: AccountForm, onIntent: (AccountIntent) -> Unit) {
    when (form.deleteStep) {
        AccountDeleteStep.None -> Unit
        AccountDeleteStep.Explain -> AlertDialog(
            onDismissRequest = { onIntent(AccountIntent.CancelDelete) },
            title = { Text(stringResource(Res.string.account_delete_title)) },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.account_delete_explain), style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onIntent(AccountIntent.SetAlsoDeleteLocal(!form.alsoDeleteLocal)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = form.alsoDeleteLocal, onCheckedChange = { onIntent(AccountIntent.SetAlsoDeleteLocal(it)) })
                        Text(
                            stringResource(Res.string.account_delete_also_local),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onIntent(AccountIntent.DeleteContinue) }) { Text(stringResource(Res.string.account_delete_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(AccountIntent.CancelDelete) }) { Text(stringResource(Res.string.account_cancel)) }
            },
        )
        AccountDeleteStep.Confirm -> AlertDialog(
            onDismissRequest = { if (!form.busy) onIntent(AccountIntent.CancelDelete) },
            title = { Text(stringResource(Res.string.account_delete_confirm_title)) },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.account_delete_confirm_online), style = MaterialTheme.typography.bodyMedium)
                    if (form.alsoDeleteLocal) {
                        Text(stringResource(Res.string.account_delete_confirm_local), style = MaterialTheme.typography.bodyMedium)
                    }
                    form.deleteError?.let {
                        Text(stringResource(it.messageRes()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onIntent(AccountIntent.ConfirmDelete) }, enabled = !form.busy) {
                    Text(stringResource(Res.string.account_delete_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(AccountIntent.CancelDelete) }, enabled = !form.busy) { Text(stringResource(Res.string.account_cancel)) }
            },
        )
    }
}
