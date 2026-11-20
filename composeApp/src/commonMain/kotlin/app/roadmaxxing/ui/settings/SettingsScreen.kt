package app.roadmaxxing.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.roadmaxxing.platform.openLanguageSettings
import app.roadmaxxing.platform.supportsPerAppLanguage
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.settings_about_legal_hint
import app.roadmaxxing.resources.settings_about_title
import app.roadmaxxing.resources.settings_about_version
import app.roadmaxxing.resources.settings_back
import app.roadmaxxing.resources.settings_cancel
import app.roadmaxxing.resources.settings_data_title
import app.roadmaxxing.resources.settings_delete
import app.roadmaxxing.resources.settings_delete_body
import app.roadmaxxing.resources.settings_delete_confirm_body
import app.roadmaxxing.resources.settings_delete_confirm_button
import app.roadmaxxing.resources.settings_delete_confirm_title
import app.roadmaxxing.resources.settings_delete_body_signed_in
import app.roadmaxxing.resources.settings_delete_cloud_checkbox
import app.roadmaxxing.resources.settings_delete_cloud_deleting
import app.roadmaxxing.resources.settings_delete_cloud_failed
import app.roadmaxxing.resources.settings_delete_cloud_hint
import app.roadmaxxing.resources.settings_delete_continue
import app.roadmaxxing.resources.settings_delete_failed
import app.roadmaxxing.resources.settings_delete_field_label
import app.roadmaxxing.resources.settings_delete_hint
import app.roadmaxxing.resources.settings_delete_title
import app.roadmaxxing.resources.settings_delete_word
import app.roadmaxxing.resources.settings_deleting
import app.roadmaxxing.resources.settings_export
import app.roadmaxxing.resources.settings_export_failed
import app.roadmaxxing.resources.settings_export_hint
import app.roadmaxxing.resources.settings_export_nothing
import app.roadmaxxing.resources.settings_exporting
import app.roadmaxxing.resources.settings_language_name
import app.roadmaxxing.resources.settings_language_row
import app.roadmaxxing.resources.settings_language_title
import app.roadmaxxing.resources.settings_notifications_title
import app.roadmaxxing.resources.settings_quiet_end
import app.roadmaxxing.resources.settings_quiet_hours
import app.roadmaxxing.resources.settings_quiet_hours_hint
import app.roadmaxxing.resources.settings_quiet_start
import app.roadmaxxing.resources.settings_time_cancel
import app.roadmaxxing.resources.settings_time_ok
import app.roadmaxxing.resources.settings_time_pick_end
import app.roadmaxxing.resources.settings_time_pick_start
import app.roadmaxxing.resources.settings_title
import app.roadmaxxing.resources.settings_unit_km
import app.roadmaxxing.resources.settings_unit_mi
import app.roadmaxxing.resources.settings_units_title
import app.roadmaxxing.shared.settings.DistanceUnit
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.ScreenClass
import app.roadmaxxing.ui.components.InfoCard
import app.roadmaxxing.ui.components.SecondaryText
import app.roadmaxxing.ui.currentScreenClass
import app.roadmaxxing.ui.profile.APP_VERSION
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Maximum content width on medium and expanded windows. */
private val ContentWidth = 640.dp

/**
 * Full-screen settings overlay opened from the Profile tab. Opaque [Surface] in a non-platform-width [Dialog]
 * (same pattern as the legal and privacy screens); system back closes it.
 */
@Composable
fun SettingsOverlay(onClose: () -> Unit) {
    val viewModel = koinViewModel<SettingsViewModel>()
    val state by viewModel.state.collectAsState()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            SettingsScreen(state = state, onIntent = viewModel::onIntent, onBack = onClose)
        }
    }
}

@Composable
fun SettingsScreen(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit, onBack: () -> Unit) {
    val compact = currentScreenClass() == ScreenClass.Compact
    val horizontalPadding = if (compact) 20.dp else 32.dp
    val maxWidth = if (compact) Dp.Infinity else ContentWidth
    val snackbar = remember { SnackbarHostState() }
    val message = when (state.exportMessage) {
        ExportMessage.NothingToExport -> stringResource(Res.string.settings_export_nothing)
        ExportMessage.Failed -> stringResource(Res.string.settings_export_failed)
        null -> null
    }
    LaunchedEffect(state.exportMessage) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onIntent(SettingsIntent.DismissExportMessage)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text("< " + stringResource(Res.string.settings_back)) }
            Text(
                text = stringResource(Res.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            UnitsCard(state, onIntent)
            if (supportsPerAppLanguage) LanguageCard()
            NotificationsCard(state, onIntent)
            DataCard(state, onIntent)
            AboutCard()
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    state.editingBoundary?.let { boundary ->
        val minute = if (boundary == QuietBoundary.Start) state.quietHours.startMinute else state.quietHours.endMinute
        QuietTimeDialog(
            boundary = boundary,
            initialMinute = minute,
            onConfirm = { onIntent(SettingsIntent.SetBoundaryTime(boundary, it)) },
            onDismiss = { onIntent(SettingsIntent.EditBoundary(null)) },
        )
    }
    when (state.deleteStep) {
        DeleteStep.None -> Unit
        DeleteStep.Explain -> DeleteExplainDialog(
            signedIn = state.signedIn,
            alsoDeleteCloud = state.alsoDeleteCloud,
            onAlsoDeleteCloud = { onIntent(SettingsIntent.SetAlsoDeleteCloud(it)) },
            onContinue = { onIntent(SettingsIntent.DeleteContinue) },
            onCancel = { onIntent(SettingsIntent.CancelDelete) },
        )
        DeleteStep.Confirm -> DeleteConfirmDialog(
            erasing = state.erasing,
            failed = state.eraseFailed,
            cloudFailed = state.cloudDeleteFailed,
            deletingCloud = state.signedIn && state.alsoDeleteCloud,
            onConfirm = { onIntent(SettingsIntent.ConfirmDelete) },
            onCancel = { onIntent(SettingsIntent.CancelDelete) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    InfoCard(stringResource(Res.string.settings_units_title)) {
        val options = listOf(
            DistanceUnit.Kilometers to stringResource(Res.string.settings_unit_km),
            DistanceUnit.Miles to stringResource(Res.string.settings_unit_mi),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (unit, label) ->
                SegmentedButton(
                    selected = state.unit == unit,
                    onClick = { onIntent(SettingsIntent.SetUnit(unit)) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun LanguageCard() {
    InfoCard(stringResource(Res.string.settings_language_title)) {
        NavRow(
            title = stringResource(Res.string.settings_language_row),
            subtitle = stringResource(Res.string.settings_language_name),
            onClick = { openLanguageSettings() },
        )
    }
}

@Composable
private fun NotificationsCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    val quiet = state.quietHours
    val title = stringResource(Res.string.settings_quiet_hours)
    InfoCard(stringResource(Res.string.settings_notifications_title)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = quiet.enabled,
                    role = Role.Switch,
                    onValueChange = { onIntent(SettingsIntent.SetQuietEnabled(it)) },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = quiet.enabled, onCheckedChange = null)
        }
        SecondaryText(stringResource(Res.string.settings_quiet_hours_hint))
        if (quiet.enabled) {
            NavRow(
                title = stringResource(Res.string.settings_quiet_start),
                subtitle = formatMinute(quiet.startMinute),
                onClick = { onIntent(SettingsIntent.EditBoundary(QuietBoundary.Start)) },
            )
            NavRow(
                title = stringResource(Res.string.settings_quiet_end),
                subtitle = formatMinute(quiet.endMinute),
                onClick = { onIntent(SettingsIntent.EditBoundary(QuietBoundary.End)) },
            )
        }
    }
}

@Composable
private fun DataCard(state: SettingsUiState, onIntent: (SettingsIntent) -> Unit) {
    InfoCard(stringResource(Res.string.settings_data_title)) {
        NavRow(
            title = stringResource(Res.string.settings_export),
            subtitle = stringResource(Res.string.settings_export_hint),
            onClick = { onIntent(SettingsIntent.Export) },
            enabled = !state.exporting && !state.erasing,
            busyLabel = if (state.exporting) stringResource(Res.string.settings_exporting) else null,
        )
        NavRow(
            title = stringResource(Res.string.settings_delete),
            subtitle = stringResource(Res.string.settings_delete_hint),
            onClick = { onIntent(SettingsIntent.AskDelete) },
            enabled = !state.exporting && !state.erasing,
            destructive = true,
            busyLabel = if (state.erasing) stringResource(Res.string.settings_deleting) else null,
        )
    }
}

@Composable
private fun AboutCard() {
    InfoCard(stringResource(Res.string.settings_about_title)) {
        Text(
            text = stringResource(Res.string.settings_about_version, APP_VERSION),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SecondaryText(stringResource(Res.string.settings_about_legal_hint))
    }
}

/** Tappable row: title, optional subtitle, chevron, or a spinner with [busyLabel] while busy. */
@Composable
private fun NavRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    busyLabel: String? = null,
) {
    val titleColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            val sub = busyLabel ?: subtitle
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RoadmaxxingTheme.extraColors.textSecondary,
                )
            }
        }
        if (busyLabel != null) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).semantics { contentDescription = busyLabel },
                strokeWidth = 2.dp,
            )
        } else {
            Text(text = ">", style = MaterialTheme.typography.bodyLarge, color = RoadmaxxingTheme.extraColors.textSecondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeDialog(
    boundary: QuietBoundary,
    initialMinute: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (boundary == QuietBoundary.Start) Res.string.settings_time_pick_start else Res.string.settings_time_pick_end,
                ),
            )
        },
        text = { Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = pickerState, colors = timePickerColors()) } },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) }) {
                Text(stringResource(Res.string.settings_time_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.settings_time_cancel)) } },
    )
}

/** Brand-green time picker: selected hour/minute box in Volt with dark text instead of the default purple. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun timePickerColors() = TimePickerDefaults.colors(
    timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primary,
    timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun DeleteExplainDialog(
    signedIn: Boolean,
    alsoDeleteCloud: Boolean,
    onAlsoDeleteCloud: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.settings_delete_title)) },
        text = {
            if (!signedIn) {
                Text(stringResource(Res.string.settings_delete_body))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.settings_delete_body_signed_in))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = alsoDeleteCloud, role = Role.Checkbox, onValueChange = onAlsoDeleteCloud),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = alsoDeleteCloud, onCheckedChange = null)
                        Text(
                            text = stringResource(Res.string.settings_delete_cloud_checkbox),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    SecondaryText(stringResource(Res.string.settings_delete_cloud_hint))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onContinue,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(Res.string.settings_delete_continue)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.settings_cancel)) } },
    )
}

/**
 * Second step: the user must type the confirmation word. A typed word (not just a second button) makes an
 * accidental double tap impossible, which matters because the erase has no backup and cannot be undone.
 */
@Composable
private fun DeleteConfirmDialog(
    erasing: Boolean,
    failed: Boolean,
    cloudFailed: Boolean,
    deletingCloud: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val word = stringResource(Res.string.settings_delete_word)
    var typed by remember { mutableStateOf("") }
    val matches = normalizeWord(typed) == normalizeWord(word)
    AlertDialog(
        onDismissRequest = { if (!erasing) onCancel() },
        title = { Text(stringResource(Res.string.settings_delete_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.settings_delete_confirm_body, word))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    enabled = !erasing,
                    label = { Text(stringResource(Res.string.settings_delete_field_label)) },
                )
                if (erasing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(if (deletingCloud) Res.string.settings_delete_cloud_deleting else Res.string.settings_deleting),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (cloudFailed) {
                    Text(
                        text = stringResource(Res.string.settings_delete_cloud_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (failed) {
                    Text(
                        text = stringResource(Res.string.settings_delete_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = matches && !erasing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text(stringResource(Res.string.settings_delete_confirm_button)) }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !erasing) { Text(stringResource(Res.string.settings_cancel)) } },
    )
}

/** Case- and Turkish-dotted-i-insensitive comparison key ("sil", "SİL" and "SIL" all match). */
private fun normalizeWord(text: String): String =
    text.trim().replace('İ', 'I').replace('ı', 'I').replace('i', 'I').uppercase()

private fun formatMinute(minuteOfDay: Int): String {
    val h = (minuteOfDay / 60).toString().padStart(2, '0')
    val m = (minuteOfDay % 60).toString().padStart(2, '0')
    return "$h:$m"
}
