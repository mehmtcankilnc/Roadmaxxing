package app.roadmaxxing.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.conflict_body
import app.roadmaxxing.resources.conflict_body_no_email
import app.roadmaxxing.resources.conflict_cancel
import app.roadmaxxing.resources.conflict_confirm_back
import app.roadmaxxing.resources.conflict_confirm_body
import app.roadmaxxing.resources.conflict_confirm_button
import app.roadmaxxing.resources.conflict_confirm_title
import app.roadmaxxing.resources.conflict_merge
import app.roadmaxxing.resources.conflict_replace
import app.roadmaxxing.resources.conflict_title
import app.roadmaxxing.shared.sync.AccountConflict
import app.roadmaxxing.shared.sync.ConflictChoice
import org.jetbrains.compose.resources.stringResource

/**
 * K2: another account signed in on a phone whose data belongs to a different account. Must be resolved: outside
 * taps do nothing and system back counts as "Cancel and sign out". Replacing local data asks for a second
 * confirmation because it deletes this phone's drives.
 */
@Composable
fun AccountConflictDialog(conflict: AccountConflict, confirmReplace: Boolean, onIntent: (AccountIntent) -> Unit) {
    val noDismiss = DialogProperties(dismissOnClickOutside = false)
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { onIntent(AccountIntent.SetConflictConfirm(false)) },
            properties = noDismiss,
            title = { Text(stringResource(Res.string.conflict_confirm_title)) },
            text = {
                Text(
                    stringResource(Res.string.conflict_confirm_body, conflict.localDriveCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = { onIntent(AccountIntent.ResolveConflict(ConflictChoice.ReplaceLocal)) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(Res.string.conflict_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(AccountIntent.SetConflictConfirm(false)) }) {
                    Text(stringResource(Res.string.conflict_confirm_back))
                }
            },
        )
        return
    }
    val email = conflict.newAccountEmail
    AlertDialog(
        onDismissRequest = { onIntent(AccountIntent.ResolveConflict(ConflictChoice.Cancel)) },
        properties = noDismiss,
        title = { Text(stringResource(Res.string.conflict_title)) },
        text = {
            Text(
                text = if (email != null) stringResource(Res.string.conflict_body, conflict.localDriveCount, email)
                else stringResource(Res.string.conflict_body_no_email, conflict.localDriveCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        // Three stacked full-width actions: the labels are long, so a button row would wrap awkwardly.
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onIntent(AccountIntent.ResolveConflict(ConflictChoice.MergeLocal)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(stringResource(Res.string.conflict_merge)) }
                OutlinedButton(
                    onClick = { onIntent(AccountIntent.SetConflictConfirm(true)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.conflict_replace)) }
                TextButton(
                    onClick = { onIntent(AccountIntent.ResolveConflict(ConflictChoice.Cancel)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.conflict_cancel)) }
            }
        },
    )
}
