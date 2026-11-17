package app.roadmaxxing.ui.drives

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.main.DetailTarget
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.drive_line
import app.roadmaxxing.resources.drive_status_pending
import app.roadmaxxing.resources.drive_status_recording
import app.roadmaxxing.resources.drive_verdict_drive
import app.roadmaxxing.resources.drive_verdict_not_drive
import app.roadmaxxing.resources.drives_empty
import app.roadmaxxing.resources.drives_title
import app.roadmaxxing.shared.domain.DriveRecord
import app.roadmaxxing.shared.domain.DriveStatus
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.ui.components.SecondaryText
import app.roadmaxxing.ui.components.TabTitle
import app.roadmaxxing.ui.main.MainUiState
import app.roadmaxxing.ui.main.formatDateTime
import app.roadmaxxing.ui.main.formatDecimal
import org.jetbrains.compose.resources.stringResource

/** Drives tab: every recorded drive with distance, duration and the km it newly explored. */
@Composable
fun DrivesTab(state: MainUiState, onOpenDrive: (Long) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabTitle(stringResource(Res.string.drives_title))
        if (state.drives.isEmpty()) {
            SecondaryText(stringResource(Res.string.drives_empty))
        }
        state.drives.forEachIndexed { i, drive ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            DriveRow(drive, selected = state.detail == DetailTarget.Drive(drive.id), onClick = { onOpenDrive(drive.id) })
        }
    }
}

@Composable
private fun DriveRow(drive: DriveRecord, selected: Boolean, onClick: () -> Unit) {
    val sep = stringResource(Res.string.decimal_separator)
    val summary = drive.summary
    val (label, isDrive) = when {
        drive.status == DriveStatus.Recording -> stringResource(Res.string.drive_status_recording) to true
        drive.status == DriveStatus.Pending || summary == null -> stringResource(Res.string.drive_status_pending) to true
        summary.verdict == DriveVerdict.Drive -> stringResource(Res.string.drive_verdict_drive) to true
        else -> stringResource(Res.string.drive_verdict_not_drive) to false
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) RoadmaxxingTheme.extraColors.surfaceRaised else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
