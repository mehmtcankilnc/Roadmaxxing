package app.roadmaxxing.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.roadmaxxing.platform.TrackingPlatform
import app.roadmaxxing.platform.rememberPermissionStates
import app.roadmaxxing.resources.legal_card_title
import app.roadmaxxing.resources.permission_approximate
import app.roadmaxxing.resources.permission_approximate_hint
import app.roadmaxxing.resources.permission_blocked_hint
import app.roadmaxxing.resources.permission_open_settings
import app.roadmaxxing.resources.profile_privacy_policy
import app.roadmaxxing.resources.profile_terms_of_use
import app.roadmaxxing.ui.legal.LegalDocument
import app.roadmaxxing.ui.legal.LegalScreen
import app.roadmaxxing.ui.privacy.PrivacyZonesOverlay
import app.roadmaxxing.resources.privacy_zones_row
import app.roadmaxxing.resources.privacy_zones_row_hint
import app.roadmaxxing.resources.privacy_zones_title
import org.koin.compose.koinInject
import app.roadmaxxing.resources.settings_row
import app.roadmaxxing.resources.settings_row_hint
import app.roadmaxxing.resources.settings_title
import app.roadmaxxing.ui.settings.SettingsOverlay
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.roadmaxxing.platform.AppPermission
import app.roadmaxxing.platform.PermissionState
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.app_version
import app.roadmaxxing.resources.card_debug_open
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.last_processing
import app.roadmaxxing.resources.main_safe_drive
import app.roadmaxxing.resources.manual_start
import app.roadmaxxing.resources.manual_stop
import app.roadmaxxing.resources.permission_activity
import app.roadmaxxing.resources.permission_allow
import app.roadmaxxing.resources.permission_background_hint
import app.roadmaxxing.resources.permission_background_location
import app.roadmaxxing.resources.permission_granted
import app.roadmaxxing.resources.permission_location
import app.roadmaxxing.resources.permission_not_needed
import app.roadmaxxing.resources.permission_notifications
import app.roadmaxxing.resources.permissions_title
import app.roadmaxxing.resources.tab_profile
import app.roadmaxxing.resources.tracking_idle
import app.roadmaxxing.resources.tracking_probing
import app.roadmaxxing.resources.tracking_processing
import app.roadmaxxing.resources.tracking_recording
import app.roadmaxxing.resources.tracking_title
import app.roadmaxxing.shared.drive.TrackingStatus
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.card.debug.CardPreviewDialog
import app.roadmaxxing.ui.components.InfoCard
import app.roadmaxxing.ui.components.SecondaryText
import app.roadmaxxing.ui.components.TabTitle
import app.roadmaxxing.ui.main.MainIntent
import app.roadmaxxing.ui.main.MainUiState
import app.roadmaxxing.ui.main.formatDecimal
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Profile tab: permissions, recording status (debug start/stop), safe-drive note and version. */
@Composable
fun ProfileTab(state: MainUiState, onIntent: (MainIntent) -> Unit, onRequestPermission: (AppPermission) -> Unit) {
    var legalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    var showPrivacyZones by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TabTitle(stringResource(Res.string.tab_profile))
        PermissionsCard(onRequestPermission)
        RecordingCard(state, onIntent)
        SettingsCard { showSettings = true }
        PrivacyZonesCard { showPrivacyZones = true }
        LegalCard { legalDocument = it }
        Text(
            text = stringResource(Res.string.main_safe_drive),
            style = MaterialTheme.typography.labelLarge,
            color = RoadmaxxingTheme.extraColors.textSecondary,
        )
        Text(
            text = stringResource(Res.string.app_version, APP_VERSION),
            style = MaterialTheme.typography.labelLarge,
            color = RoadmaxxingTheme.extraColors.textSecondary,
        )
    }
    if (showPrivacyZones) PrivacyZonesOverlay(onClose = { showPrivacyZones = false })
    if (showSettings) SettingsOverlay(onClose = { showSettings = false })
    legalDocument?.let { doc ->
        // Full-screen overlay held in local state; system back closes it.
        Dialog(
            onDismissRequest = { legalDocument = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                LegalScreen(document = doc, onBack = { legalDocument = null })
            }
        }
    }
}

@Composable
private fun RecordingCard(state: MainUiState, onIntent: (MainIntent) -> Unit) {
    val sep = stringResource(Res.string.decimal_separator)
    InfoCard(stringResource(Res.string.tracking_title)) {
        val tracking = state.tracking
        val text = when (tracking) {
            TrackingStatus.Idle -> stringResource(Res.string.tracking_idle)
            is TrackingStatus.Probing -> stringResource(Res.string.tracking_probing)
            is TrackingStatus.Recording -> stringResource(Res.string.tracking_recording, tracking.pointCount.toString())
            is TrackingStatus.Processing -> stringResource(Res.string.tracking_processing)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (tracking is TrackingStatus.Recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        state.lastProcessing?.let {
            SecondaryText(
                stringResource(
                    Res.string.last_processing,
                    formatDecimal(it.afterEndMs / 1000.0, 1, sep),
                    formatDecimal(it.processingMs / 1000.0, 1, sep),
                ),
            )
        }
        if (state.isDebugBuild) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onIntent(MainIntent.ManualStart) }, enabled = tracking !is TrackingStatus.Recording) {
                    Text(stringResource(Res.string.manual_start))
                }
                OutlinedButton(onClick = { onIntent(MainIntent.ManualStop) }, enabled = tracking is TrackingStatus.Recording) {
                    Text(stringResource(Res.string.manual_stop))
                }
            }
            var showCardPreview by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { showCardPreview = true }) { Text(stringResource(Res.string.card_debug_open)) }
            if (showCardPreview) CardPreviewDialog(onClose = { showCardPreview = false })
        }
    }
}

@Composable
private fun PermissionsCard(onRequestPermission: (AppPermission) -> Unit) {
    val platform = koinInject<TrackingPlatform>()
    val permissions by rememberPermissionStates()
    InfoCard(stringResource(Res.string.permissions_title)) {
        AppPermission.entries.forEach { permission ->
            val st = permissions[permission] ?: PermissionState.Denied
            // Background location can only be asked once foreground location is granted (precise or approximate).
            val locationState = permissions[AppPermission.Location]
            val askable = permission != AppPermission.BackgroundLocation ||
                locationState == PermissionState.Granted || locationState == PermissionState.Approximate
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(permission.label()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                when (st) {
                    PermissionState.Granted -> Text(
                        stringResource(Res.string.permission_granted),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    PermissionState.NotNeeded -> SecondaryText(stringResource(Res.string.permission_not_needed))
                    PermissionState.Denied -> PermissionButton(
                        text = stringResource(Res.string.permission_allow),
                        enabled = askable,
                        onClick = { onRequestPermission(permission) },
                    )
                    PermissionState.Blocked, PermissionState.Approximate -> PermissionButton(
                        text = stringResource(Res.string.permission_open_settings),
                        enabled = true,
                        onClick = { platform.openAppSettings() },
                    )
                }
            }
            when {
                st == PermissionState.Approximate -> {
                    Text(
                        stringResource(Res.string.permission_approximate),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    SecondaryText(stringResource(Res.string.permission_approximate_hint))
                }
                st == PermissionState.Blocked -> SecondaryText(stringResource(Res.string.permission_blocked_hint))
                permission == AppPermission.BackgroundLocation && st == PermissionState.Denied ->
                    SecondaryText(stringResource(Res.string.permission_background_hint))
            }
        }
    }
}

@Composable
private fun PermissionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) { Text(text) }
}

@Composable
private fun SettingsCard(onOpen: () -> Unit) {
    InfoCard(stringResource(Res.string.settings_row)) {
        SecondaryText(stringResource(Res.string.settings_row_hint))
        LegalRow(stringResource(Res.string.settings_title), onOpen)
    }
}

@Composable
private fun PrivacyZonesCard(onOpen: () -> Unit) {
    InfoCard(stringResource(Res.string.privacy_zones_row)) {
        SecondaryText(stringResource(Res.string.privacy_zones_row_hint))
        LegalRow(stringResource(Res.string.privacy_zones_title), onOpen)
    }
}

@Composable
private fun LegalCard(onOpen: (LegalDocument) -> Unit) {
    InfoCard(stringResource(Res.string.legal_card_title)) {
        LegalRow(stringResource(Res.string.profile_privacy_policy)) { onOpen(LegalDocument.Privacy) }
        LegalRow(stringResource(Res.string.profile_terms_of_use)) { onOpen(LegalDocument.Terms) }
    }
}

@Composable
private fun LegalRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(text = ">", style = MaterialTheme.typography.bodyLarge, color = RoadmaxxingTheme.extraColors.textSecondary)
    }
}

private fun AppPermission.label(): StringResource = when (this) {
    AppPermission.Location -> Res.string.permission_location
    AppPermission.BackgroundLocation -> Res.string.permission_background_location
    AppPermission.ActivityRecognition -> Res.string.permission_activity
    AppPermission.Notifications -> Res.string.permission_notifications
}
