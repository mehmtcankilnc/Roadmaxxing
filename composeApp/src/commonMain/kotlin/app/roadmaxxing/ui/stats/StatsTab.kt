package app.roadmaxxing.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.progress_districts
import app.roadmaxxing.resources.progress_loading
import app.roadmaxxing.resources.progress_unavailable
import app.roadmaxxing.resources.tab_stats
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.components.RegionRow
import app.roadmaxxing.ui.components.SecondaryText
import app.roadmaxxing.ui.components.TabTitle
import app.roadmaxxing.ui.main.MainUiState
import app.roadmaxxing.ui.main.ProgressState
import org.jetbrains.compose.resources.stringResource

/** Stats tab: province and district percentages with the major / local road breakdown. */
@Composable
fun StatsTab(state: MainUiState) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabTitle(stringResource(Res.string.tab_stats))
        when (val progress = state.progress) {
            ProgressState.Loading -> SecondaryText(stringResource(Res.string.progress_loading))
            ProgressState.Unavailable -> SecondaryText(stringResource(Res.string.progress_unavailable))
            is ProgressState.Ready -> {
                progress.snapshot.province?.let { RegionRow(it, emphasized = true) }
                if (progress.snapshot.districts.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = stringResource(Res.string.progress_districts),
                        style = MaterialTheme.typography.labelLarge,
                        color = RoadmaxxingTheme.extraColors.textSecondary,
                    )
                    // Seyhan (pilot district) first, the rest by completion.
                    progress.snapshot.districts
                        .sortedBy { it.region.name != SEYHAN }
                        .forEach { RegionRow(it, emphasized = it.region.name == SEYHAN) }
                }
            }
        }
    }
}

private const val SEYHAN = "Seyhan"
