package app.roadmaxxing.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.roadmaxxing.platform.PermissionState
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.chip_probing
import app.roadmaxxing.resources.chip_processing
import app.roadmaxxing.resources.chip_recording
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.invite_body
import app.roadmaxxing.resources.invite_permissions
import app.roadmaxxing.resources.invite_title
import app.roadmaxxing.resources.percent_value
import app.roadmaxxing.resources.progress_loading
import app.roadmaxxing.resources.progress_province_line
import app.roadmaxxing.resources.progress_unavailable
import app.roadmaxxing.shared.domain.RegionProgressItem
import app.roadmaxxing.shared.drive.TrackingStatus
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.components.ExplorationBar
import app.roadmaxxing.ui.components.FloatingPanel
import app.roadmaxxing.ui.components.PERCENT_DECIMALS
import app.roadmaxxing.ui.components.PanelCorner
import app.roadmaxxing.ui.components.SecondaryText
import app.roadmaxxing.ui.main.MainUiState
import app.roadmaxxing.ui.main.ProgressState
import app.roadmaxxing.ui.main.formatDecimal
import org.jetbrains.compose.resources.stringResource

/**
 * Floating content of the Map tab: city percentage card, recording chip and, before the first drive, the invitation.
 * The map itself is drawn behind by the shell.
 */
@Composable
fun ExploreOverlay(
    state: MainUiState,
    explore: ExploreUiState,
    onOpenPermissions: () -> Unit,
    onOpenCity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CityCard(state.progress, onOpenCity)
        RecordingChip(state.tracking)
        if (explore.loaded && explore.segmentCount == 0) {
            FirstRoadInvite(state, onOpenPermissions)
        }
    }
}

@Composable
private fun CityCard(progress: ProgressState, onClick: () -> Unit) {
    val sep = stringResource(Res.string.decimal_separator)
    FloatingPanel(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (progress) {
                ProgressState.Loading -> SecondaryText(stringResource(Res.string.progress_loading))
                ProgressState.Unavailable -> SecondaryText(stringResource(Res.string.progress_unavailable))
                is ProgressState.Ready -> progress.snapshot.province?.let { CityContent(it, sep) }
                    ?: SecondaryText(stringResource(Res.string.progress_unavailable))
            }
        }
    }
}

@Composable
private fun CityContent(province: RegionProgressItem, sep: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = province.region.nameTr ?: province.region.name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        // Tabular figures keep the number from jittering while it counts up.
        Text(
            text = stringResource(Res.string.percent_value, formatDecimal(province.percent, PERCENT_DECIMALS, sep)),
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
    ExplorationBar(province.percent)
    SecondaryText(
        stringResource(
            Res.string.progress_province_line,
            formatDecimal(province.percentMajor, PERCENT_DECIMALS, sep),
            formatDecimal(province.percentLocal, PERCENT_DECIMALS, sep),
        ),
    )
}

/** Small status chip while the app is recording or updating the map; nothing when idle. */
@Composable
private fun RecordingChip(tracking: TrackingStatus) {
    val label = when (tracking) {
        TrackingStatus.Idle -> return
        is TrackingStatus.Probing -> stringResource(Res.string.chip_probing)
        is TrackingStatus.Recording -> stringResource(Res.string.chip_recording)
        is TrackingStatus.Processing -> stringResource(Res.string.chip_processing)
    }
    Surface(shape = RoundedCornerShape(percent = 50), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

/** Before the first drive: invites the user to drive (not to invite friends). */
@Composable
private fun FirstRoadInvite(state: MainUiState, onOpenPermissions: () -> Unit) {
    val permissionsMissing = state.permissions.values.any { it == PermissionState.Denied }
    FloatingPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.invite_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.invite_body),
                style = MaterialTheme.typography.bodyMedium,
                color = RoadmaxxingTheme.extraColors.textSecondary,
            )
            if (permissionsMissing) {
                Button(
                    onClick = onOpenPermissions,
                    shape = RoundedCornerShape(PanelCorner),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(stringResource(Res.string.invite_permissions)) }
            }
        }
    }
}
