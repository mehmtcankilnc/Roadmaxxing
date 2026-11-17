package app.roadmaxxing.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.percent_value
import app.roadmaxxing.resources.progress_province_line
import app.roadmaxxing.shared.domain.RegionProgressItem
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.main.formatDecimal
import org.jetbrains.compose.resources.stringResource

/** Corner radius of every floating panel (docs/DESIGN.md). */
val PanelCorner = 32.dp

/** Decimals shown for region percentages. */
const val PERCENT_DECIMALS = 2

/** A floating panel on top of the map. */
@Composable
fun FloatingPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(PanelCorner),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        content = content,
    )
}

/** A titled card inside a panel. */
@Composable
fun InfoCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = RoadmaxxingTheme.extraColors.surfaceRaised,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
fun TabTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = RoadmaxxingTheme.extraColors.textSecondary,
    )
}

/** Exploration progress bar in the brand green. */
@Composable
fun ExplorationBar(percent: Double, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(8.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = RoadmaxxingTheme.extraColors.roadUnexplored,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** Region name and percent, with the major / local road breakdown underneath. */
@Composable
fun RegionRow(item: RegionProgressItem, emphasized: Boolean) {
    val sep = stringResource(Res.string.decimal_separator)
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
        if (emphasized) ExplorationBar(item.percent)
        SecondaryText(
            stringResource(
                Res.string.progress_province_line,
                formatDecimal(item.percentMajor, PERCENT_DECIMALS, sep),
                formatDecimal(item.percentLocal, PERCENT_DECIMALS, sep),
            ),
        )
    }
}
