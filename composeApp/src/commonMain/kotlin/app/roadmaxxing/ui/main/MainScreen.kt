package app.roadmaxxing.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadmaxxing.platform.AppPermission
import app.roadmaxxing.platform.PermissionState
import app.roadmaxxing.platform.rememberPermissionRequester
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.app_name
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.drive_line
import app.roadmaxxing.resources.drive_status_pending
import app.roadmaxxing.resources.drive_status_recording
import app.roadmaxxing.resources.drive_verdict_drive
import app.roadmaxxing.resources.drive_verdict_not_drive
import app.roadmaxxing.resources.drives_empty
import app.roadmaxxing.resources.drives_title
import app.roadmaxxing.resources.last_processing
import app.roadmaxxing.resources.main_phase_label
import app.roadmaxxing.resources.main_safe_drive
import app.roadmaxxing.resources.manual_start
import app.roadmaxxing.resources.manual_stop
import app.roadmaxxing.resources.percent_value
import app.roadmaxxing.resources.permission_activity
import app.roadmaxxing.resources.permission_allow
import app.roadmaxxing.resources.permission_background_hint
import app.roadmaxxing.resources.permission_background_location
import app.roadmaxxing.resources.permission_granted
import app.roadmaxxing.resources.permission_location
import app.roadmaxxing.resources.permission_not_needed
import app.roadmaxxing.resources.permission_notifications
import app.roadmaxxing.resources.permissions_title
import app.roadmaxxing.resources.progress_districts
import app.roadmaxxing.resources.progress_loading
import app.roadmaxxing.resources.progress_province_line
import app.roadmaxxing.resources.progress_title
import app.roadmaxxing.resources.progress_unavailable
import app.roadmaxxing.resources.tracking_idle
import app.roadmaxxing.resources.tracking_probing
import app.roadmaxxing.resources.tracking_processing
import app.roadmaxxing.resources.tracking_recording
import app.roadmaxxing.resources.tracking_title
import app.roadmaxxing.shared.domain.DriveRecord
import app.roadmaxxing.shared.domain.DriveStatus
import app.roadmaxxing.shared.domain.RegionProgressItem
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.drive.TrackingStatus
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.ScreenClass
import app.roadmaxxing.ui.currentScreenClass
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MainScreen(viewModel: MainViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requestPermission = rememberPermissionRequester { viewModel.onIntent(MainIntent.PermissionsChanged) }
    // Permissions can change in system settings while the app is in the background.
    LifecycleResumeEffect(Unit) {
        viewModel.onIntent(MainIntent.PermissionsChanged)
        onPauseOrDispose { }
    }
    MainContent(
        state = state,
        screenClass = currentScreenClass(),
        onIntent = viewModel::onIntent,
        onRequestPermission = requestPermission,
    )
}

@Composable
internal fun MainContent(
    state: MainUiState,
    screenClass: ScreenClass,
    onIntent: (MainIntent) -> Unit,
    onRequestPermission: (AppPermission) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        when (screenClass) {
            ScreenClass.Compact -> SinglePane(state, onIntent, onRequestPermission, horizontalPadding = 16.dp)
            ScreenClass.Medium -> SinglePane(state, onIntent, onRequestPermission, horizontalPadding = 32.dp)
            ScreenClass.Expanded -> TwoPane(state, onIntent, onRequestPermission)
        }
    }
}

/** Compact and medium widths: one column, width capped so lines stay readable. */
@Composable
private fun SinglePane(
    state: MainUiState,
    onIntent: (MainIntent) -> Unit,
    onRequestPermission: (AppPermission) -> Unit,
    horizontalPadding: Dp,
) {
    Column(
        modifier = Modifier
            .widthIn(max = ReadableContentWidth)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header()
        TrackingCard(state, onIntent)
        ProgressCard(state)
        PermissionsCard(state, onRequestPermission)
        DrivesCard(state)
        SafeDriveNote()
    }
}

/** Expanded width: status and permissions on the left, progress and drives on the right. */
@Composable
private fun TwoPane(state: MainUiState, onIntent: (MainIntent) -> Unit, onRequestPermission: (AppPermission) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            TrackingCard(state, onIntent)
            PermissionsCard(state, onRequestPermission)
            SafeDriveNote()
        }
        Column(
            modifier = Modifier.weight(1.2f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProgressCard(state)
            DrivesCard(state)
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(Res.string.main_phase_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(Res.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun SecondaryText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = RoadmaxxingTheme.extraColors.textSecondary)
}

@Composable
private fun TrackingCard(state: MainUiState, onIntent: (MainIntent) -> Unit) {
    val sep = stringResource(Res.string.decimal_separator)
    Card(stringResource(Res.string.tracking_title)) {
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
private fun ProgressCard(state: MainUiState) {
    val sep = stringResource(Res.string.decimal_separator)
    Card(stringResource(Res.string.progress_title)) {
        when (val progress = state.progress) {
            ProgressState.Loading -> SecondaryText(stringResource(Res.string.progress_loading))
            ProgressState.Unavailable -> SecondaryText(stringResource(Res.string.progress_unavailable))
            is ProgressState.Ready -> {
                val province = progress.snapshot.province
                if (province != null) {
                    RegionRow(province, sep, emphasized = true)
                }
                if (progress.snapshot.districts.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = stringResource(Res.string.progress_districts),
                        style = MaterialTheme.typography.labelLarge,
                        color = RoadmaxxingTheme.extraColors.textSecondary,
                    )
                    // Seyhan (pilot district) first, the rest by completion.
                    progress.snapshot.districts.sortedBy { it.region.name != SEYHAN }.forEach { RegionRow(it, sep, emphasized = it.region.name == SEYHAN) }
                }
            }
        }
    }
}

@Composable
private fun RegionRow(item: RegionProgressItem, sep: String, emphasized: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.region.nameTr ?: item.region.name,
                style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(Res.string.percent_value, formatDecimal(item.percent, PERCENT_DECIMALS, sep)),
                style = if (emphasized) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (item.drivenDm > 0) MaterialTheme.colorScheme.primary else RoadmaxxingTheme.extraColors.textSecondary,
            )
        }
        if (emphasized) {
            LinearProgressIndicator(
                progress = { (item.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = RoadmaxxingTheme.extraColors.roadUnexplored,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        SecondaryText(
            stringResource(
                Res.string.progress_province_line,
                formatDecimal(item.percentMajor, PERCENT_DECIMALS, sep),
                formatDecimal(item.percentLocal, PERCENT_DECIMALS, sep),
            ),
        )
    }
}

@Composable
private fun PermissionsCard(state: MainUiState, onRequestPermission: (AppPermission) -> Unit) {
    Card(stringResource(Res.string.permissions_title)) {
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

@Composable
private fun DrivesCard(state: MainUiState) {
    Card(stringResource(Res.string.drives_title)) {
        if (state.drives.isEmpty()) {
            SecondaryText(stringResource(Res.string.drives_empty))
        }
        state.drives.forEachIndexed { i, drive ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            DriveRow(drive)
        }
    }
}

@Composable
private fun DriveRow(drive: DriveRecord) {
    val sep = stringResource(Res.string.decimal_separator)
    val summary = drive.summary
    val (label, isDrive) = when {
        drive.status == DriveStatus.Recording -> stringResource(Res.string.drive_status_recording) to true
        drive.status == DriveStatus.Pending || summary == null -> stringResource(Res.string.drive_status_pending) to true
        summary.verdict == DriveVerdict.Drive -> stringResource(Res.string.drive_verdict_drive) to true
        else -> stringResource(Res.string.drive_verdict_not_drive) to false
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatDateTime(drive.startMs),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isDrive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (summary != null) {
            SecondaryText(
                stringResource(
                    Res.string.drive_line,
                    formatDecimal(summary.distanceM / 1000.0, 2, sep),
                    formatDecimal(summary.durationMs / 60_000.0, 1, sep),
                    formatDecimal(summary.newLengthDm / 10_000.0, 2, sep),
                ),
            )
        }
    }
}

@Composable
private fun SafeDriveNote() {
    Text(
        text = stringResource(Res.string.main_safe_drive),
        style = MaterialTheme.typography.labelLarge,
        color = RoadmaxxingTheme.extraColors.textSecondary,
    )
}

private val ReadableContentWidth = 720.dp
private const val PERCENT_DECIMALS = 2
private const val SEYHAN = "Seyhan"
