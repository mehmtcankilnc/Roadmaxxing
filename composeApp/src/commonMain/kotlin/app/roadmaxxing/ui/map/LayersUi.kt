package app.roadmaxxing.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.layers_button
import app.roadmaxxing.resources.layers_privacy_zones
import app.roadmaxxing.resources.layers_privacy_zones_hint
import app.roadmaxxing.resources.layers_title
import app.roadmaxxing.resources.layers_undiscovered
import app.roadmaxxing.resources.layers_undiscovered_hint
import app.roadmaxxing.theme.RoadmaxxingTheme
import org.jetbrains.compose.resources.stringResource

// Three stacked diamonds; drawn here because no icon pack is a dependency.
private val LayersIcon: ImageVector =
    ImageVector.Builder(name = "layers", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close()
            moveTo(3f, 12f); lineTo(12f, 17f); lineTo(21f, 12f)
            moveTo(3f, 16f); lineTo(12f, 21f); lineTo(21f, 16f)
        }
        .build()

/** Round map button that opens the layers sheet. The touch target is 48 dp. */
@Composable
internal fun LayersButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.layers_button)
    Surface(
        modifier = modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Icon(
            imageVector = LayersIcon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayersSheet(
    showUndiscovered: Boolean,
    showPrivacyZones: Boolean,
    onShowUndiscovered: (Boolean) -> Unit,
    onShowPrivacyZones: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(Res.string.layers_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            LayerSwitchRow(
                title = stringResource(Res.string.layers_undiscovered),
                hint = stringResource(Res.string.layers_undiscovered_hint),
                checked = showUndiscovered,
                onCheckedChange = onShowUndiscovered,
            )
            LayerSwitchRow(
                title = stringResource(Res.string.layers_privacy_zones),
                hint = stringResource(Res.string.layers_privacy_zones_hint),
                checked = showPrivacyZones,
                onCheckedChange = onShowPrivacyZones,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The whole row is the switch for screen readers and touch (one 48 dp+ target), the Switch itself is decoration. */
@Composable
private fun LayerSwitchRow(title: String, hint: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = hint, style = MaterialTheme.typography.bodyMedium, color = RoadmaxxingTheme.extraColors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
