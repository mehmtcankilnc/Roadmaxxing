package app.roadmaxxing.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.roadmaxxing.platform.AppPermission
import app.roadmaxxing.platform.PermissionState
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.app_version
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
import app.roadmaxxing.ui.components.InfoCard
import app.roadmaxxing.ui.components.SecondaryText
import app.roadmaxxing.ui.components.TabTitle
import app.roadmaxxing.ui.main.MainIntent
import app.roadmaxxing.ui.main.MainUiState
import app.roadmaxxing.ui.main.formatDecimal
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Version shown in Profile. Keep in sync with `versionName` in androidApp/build.gradle.kts: common code has no
 * build-config access and `platform/` is off limits for this change.
 */
internal const val APP_VERSION = "0.1.0"

/** Profile tab: permissions, recording status (debug start/stop), safe-drive note and version. */
@Composable
fun ProfileTab(state: MainUiState, onIntent: (MainIntent) -> Unit, onRequestPermission: (AppPermission) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TabTitle(stringResource(Res.string.tab_profile))
        PermissionsCard(state, onRequestPermission)
        RecordingCard(state, onIntent)
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
        }
    }
}

@Composable
private fun PermissionsCard(state: MainUiState, onRequestPermission: (AppPermission) -> Unit) {
    InfoCard(stringResource(Res.string.permissions_title)) {
        AppPermission.entries.forEach { permission ->
            val st = state.permissions[permission] ?: PermissionState.Denied
            // Background location can only be asked once foreground location is granted.
            val askable = permission != AppPermission.BackgroundLocation ||
                state.permissions[AppPermission.Location] == PermissionState.Granted
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
                    PermissionState.Denied -> Button(
                        onClick = { onRequestPermission(permission) },
                        enabled = askable,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) { Text(stringResource(Res.string.permission_allow)) }
                }
            }
            if (permission == AppPermission.BackgroundLocation && st == PermissionState.Denied) {
                SecondaryText(stringResource(Res.string.permission_background_hint))
            }
        }
    }
}

private fun AppPermission.label(): StringResource = when (this) {
    AppPermission.Location -> Res.string.permission_location
    AppPermission.BackgroundLocation -> Res.string.permission_background_location
    AppPermission.ActivityRecognition -> Res.string.permission_activity
    AppPermission.Notifications -> Res.string.permission_notifications
}
