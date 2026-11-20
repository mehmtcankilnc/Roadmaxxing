package app.roadmaxxing.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.account_back
import app.roadmaxxing.resources.account_email
import app.roadmaxxing.resources.account_error_email_in_use
import app.roadmaxxing.resources.account_error_generic
import app.roadmaxxing.resources.account_error_invalid_email
import app.roadmaxxing.resources.account_error_network
import app.roadmaxxing.resources.account_error_recent_login
import app.roadmaxxing.resources.account_error_weak_password
import app.roadmaxxing.resources.account_error_wrong_credentials
import app.roadmaxxing.resources.account_forgot
import app.roadmaxxing.resources.account_google
import app.roadmaxxing.resources.account_mode_create
import app.roadmaxxing.resources.account_mode_sign_in
import app.roadmaxxing.resources.account_or_email
import app.roadmaxxing.resources.account_password
import app.roadmaxxing.resources.account_reassure
import app.roadmaxxing.resources.account_reset_sent
import app.roadmaxxing.resources.account_screen_title
import app.roadmaxxing.resources.account_screen_why
import app.roadmaxxing.resources.account_working
import app.roadmaxxing.shared.account.AuthError
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.ScreenClass
import app.roadmaxxing.ui.components.SecondaryText
import app.roadmaxxing.ui.currentScreenClass
import org.jetbrains.compose.resources.stringResource

private val ContentWidth = 520.dp

/**
 * Full-screen sign-in overlay: opaque [Surface] in a non-platform-width [Dialog], like the settings and privacy
 * screens. System back closes it.
 */
@Composable
fun SignInOverlay(form: AccountForm, onIntent: (AccountIntent) -> Unit) {
    Dialog(
        onDismissRequest = { onIntent(AccountIntent.CloseSignIn) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SignInScreen(form = form, onIntent = onIntent)
        }
    }
}

@Composable
fun SignInScreen(form: AccountForm, onIntent: (AccountIntent) -> Unit) {
    val compact = currentScreenClass() == ScreenClass.Compact
    val horizontalPadding = if (compact) 20.dp else 32.dp
    val maxWidth = if (compact) Dp.Infinity else ContentWidth
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = { onIntent(AccountIntent.CloseSignIn) }) { Text("< " + stringResource(Res.string.account_back)) }
            Text(
                text = stringResource(Res.string.account_screen_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            SecondaryText(stringResource(Res.string.account_screen_why))

            OutlinedButton(
                onClick = { onIntent(AccountIntent.Google) },
                enabled = !form.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.account_google)) }

            SecondaryText(stringResource(Res.string.account_or_email))

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = form.mode == AuthMode.SignIn,
                    onClick = { onIntent(AccountIntent.SetMode(AuthMode.SignIn)) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    colors = segmentColors(),
                ) { Text(stringResource(Res.string.account_mode_sign_in)) }
                SegmentedButton(
                    selected = form.mode == AuthMode.Create,
                    onClick = { onIntent(AccountIntent.SetMode(AuthMode.Create)) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    colors = segmentColors(),
                ) { Text(stringResource(Res.string.account_mode_create)) }
            }
            OutlinedTextField(
                value = form.email,
                onValueChange = { onIntent(AccountIntent.SetEmail(it)) },
                label = { Text(stringResource(Res.string.account_email)) },
                singleLine = true,
                enabled = !form.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.password,
                onValueChange = { onIntent(AccountIntent.SetPassword(it)) },
                label = { Text(stringResource(Res.string.account_password)) },
                singleLine = true,
                enabled = !form.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            form.error?.let {
                Text(
                    text = stringResource(it.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (form.resetSent) {
                Text(
                    text = stringResource(Res.string.account_reset_sent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Button(
                onClick = { onIntent(AccountIntent.Submit) },
                enabled = !form.busy && form.email.isNotBlank() && form.password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (form.busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Text(stringResource(Res.string.account_working))
                    }
                } else {
                    Text(stringResource(if (form.mode == AuthMode.Create) Res.string.account_mode_create else Res.string.account_mode_sign_in))
                }
            }
            if (form.mode == AuthMode.SignIn) {
                TextButton(onClick = { onIntent(AccountIntent.ForgotPassword) }, enabled = !form.busy) {
                    Text(stringResource(Res.string.account_forgot))
                }
            }
            Text(
                text = stringResource(Res.string.account_reassure),
                style = MaterialTheme.typography.labelLarge,
                color = RoadmaxxingTheme.extraColors.textSecondary,
            )
        }
    }
}

/** Resource for the message of an [AuthError]. */
internal fun AuthError.messageRes() = when (this) {
    AuthError.InvalidCredentials, AuthError.UserNotFound -> Res.string.account_error_wrong_credentials
    AuthError.EmailInUse -> Res.string.account_error_email_in_use
    AuthError.WeakPassword -> Res.string.account_error_weak_password
    AuthError.InvalidEmail -> Res.string.account_error_invalid_email
    AuthError.Network -> Res.string.account_error_network
    AuthError.RequiresRecentLogin -> Res.string.account_error_recent_login
    AuthError.TooManyRequests, AuthError.Unknown -> Res.string.account_error_generic
}

@Composable
private fun segmentColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary,
    activeContentColor = MaterialTheme.colorScheme.onPrimary,
    activeBorderColor = MaterialTheme.colorScheme.primary,
)
