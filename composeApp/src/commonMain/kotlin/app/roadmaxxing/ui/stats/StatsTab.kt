package app.roadmaxxing.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.card_duration_hm
import app.roadmaxxing.resources.card_duration_m
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.percent_value
import app.roadmaxxing.resources.stats_classic_avg_speed
import app.roadmaxxing.resources.stats_classic_distance
import app.roadmaxxing.resources.stats_classic_drives
import app.roadmaxxing.resources.stats_classic_max_speed
import app.roadmaxxing.resources.stats_classic_time
import app.roadmaxxing.resources.stats_classic_title
import app.roadmaxxing.resources.stats_complete
import app.roadmaxxing.resources.stats_districts_title
import app.roadmaxxing.resources.stats_empty_body
import app.roadmaxxing.resources.stats_empty_title
import app.roadmaxxing.resources.stats_header_caption
import app.roadmaxxing.resources.stats_loading
import app.roadmaxxing.resources.stats_milestone_next
import app.roadmaxxing.resources.stats_province_fallback
import app.roadmaxxing.resources.stats_tile_districts
import app.roadmaxxing.resources.stats_tile_districts_value
import app.roadmaxxing.resources.stats_tile_major
import app.roadmaxxing.resources.stats_tile_major_sub
import app.roadmaxxing.resources.stats_tile_new_week
import app.roadmaxxing.resources.stats_tile_streets
import app.roadmaxxing.resources.stats_tile_streets_total
import app.roadmaxxing.resources.stats_value_with_unit
import app.roadmaxxing.resources.tab_stats
import app.roadmaxxing.shared.settings.DistanceUnit
import app.roadmaxxing.shared.stats.StatsSnapshot
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.ScreenClass
import app.roadmaxxing.ui.components.ExplorationBar
import app.roadmaxxing.ui.components.SecondaryText
import app.roadmaxxing.ui.components.TabTitle
import app.roadmaxxing.ui.currentScreenClass
import app.roadmaxxing.ui.main.MainUiState
import app.roadmaxxing.ui.main.distanceUnitLabel
import app.roadmaxxing.ui.main.formatDecimal
import app.roadmaxxing.ui.main.formatDistanceValue
import app.roadmaxxing.ui.main.formatSpeedValue
import app.roadmaxxing.ui.main.rememberDistanceUnit
import app.roadmaxxing.ui.main.speedUnitLabel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Two columns only when there is real room; the panel on a tablet can be narrower than the screen class suggests. */
private val TwoColumnMinWidth = 640.dp
private const val HEADER_PERCENT_DECIMALS = 1

/** Stats tab: progress header, headline tiles, weekly chart, districts and a small "classic" section. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun StatsTab(state: MainUiState, onDistrictClick: (Long) -> Unit = {}) {
    val viewModel: StatsViewModel = koinViewModel()
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val unit = rememberDistanceUnit()
    val screenClass = currentScreenClass()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TabTitle(stringResource(Res.string.tab_stats))
        when (val current = ui) {
            StatsUiState.Loading -> SecondaryText(stringResource(Res.string.stats_loading))
            StatsUiState.Empty -> EmptyStats()
            is StatsUiState.Ready -> BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val twoColumns = screenClass != ScreenClass.Compact && maxWidth >= TwoColumnMinWidth
                if (twoColumns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Header(current.snapshot, unit)
                            Tiles(current.snapshot, unit)
                            ChartCard(current.snapshot, unit)
                            Classic(current.snapshot, unit)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Districts(current.snapshot, onDistrictClick)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Header(current.snapshot, unit)
                        Tiles(current.snapshot, unit)
                        ChartCard(current.snapshot, unit)
                        Districts(current.snapshot, onDistrictClick)
                        Classic(current.snapshot, unit)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStats() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = RoadmaxxingTheme.extraColors.surfaceRaised,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.stats_empty_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            SecondaryText(stringResource(Res.string.stats_empty_body))
        }
    }
}

@Composable
private fun Header(snapshot: StatsSnapshot, unit: DistanceUnit) {
    val sep = stringResource(Res.string.decimal_separator)
    val name = snapshot.provinceName ?: stringResource(Res.string.stats_province_fallback)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(
                    Res.string.percent_value,
                    formatDecimal(snapshot.provincePercent, HEADER_PERCENT_DECIMALS, sep),
                ),
                style = MaterialTheme.typography.displaySmall,
                fontSize = 56.sp,
                lineHeight = 60.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            SecondaryText(stringResource(Res.string.stats_header_caption), modifier = Modifier.padding(bottom = 10.dp))
        }
        val milestone = snapshot.nextMilestone
        if (milestone != null) {
            ExplorationBar(percent = milestone.progressFraction.coerceIn(0f, 1f) * 100.0)
            val remaining = stringResource(
                Res.string.stats_value_with_unit,
                formatDistanceValue(milestone.remainingMeters, unit, 1, sep),
                distanceUnitLabel(unit),
            )
            SecondaryText(
                stringResource(Res.string.stats_milestone_next, remaining, formatDecimal(milestone.targetPercent, 0, sep)),
            )
        } else if (snapshot.provincePercent >= 100.0) {
            ExplorationBar(percent = 100.0)
            Text(
                text = stringResource(
                    Res.string.stats_complete,
                    stringResource(Res.string.percent_value, "100"),
                    name,
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun Tiles(snapshot: StatsSnapshot, unit: DistanceUnit) {
    val sep = stringResource(Res.string.decimal_separator)
    val newWeek = stringResource(
        Res.string.stats_value_with_unit,
        formatDistanceValue(snapshot.newMetersThisWeek, unit, 1, sep),
        distanceUnitLabel(unit),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(stringResource(Res.string.stats_tile_new_week), newWeek, null, Modifier.weight(1f).fillMaxHeight())
            Tile(
                stringResource(Res.string.stats_tile_districts),
                stringResource(Res.string.stats_tile_districts_value, snapshot.districtsCompleted, snapshot.districtsTotal),
                null,
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(modifier = Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                stringResource(Res.string.stats_tile_streets),
                snapshot.newStreetsThisWeek.toString(),
                stringResource(Res.string.stats_tile_streets_total, snapshot.streetsDriven),
                Modifier.weight(1f).fillMaxHeight(),
            )
            Tile(
                stringResource(Res.string.stats_tile_major),
                stringResource(Res.string.percent_value, formatDecimal(snapshot.majorPercent, HEADER_PERCENT_DECIMALS, sep)),
                stringResource(
                    Res.string.stats_tile_major_sub,
                    formatDecimal(snapshot.localPercent, HEADER_PERCENT_DECIMALS, sep),
                ),
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun Tile(label: String, value: String, subtitle: String?, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = RoadmaxxingTheme.extraColors.surfaceRaised) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = RoadmaxxingTheme.extraColors.textSecondary)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = RoadmaxxingTheme.extraColors.textSecondary)
            }
        }
    }
}

@Composable
private fun ChartCard(snapshot: StatsSnapshot, unit: DistanceUnit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = RoadmaxxingTheme.extraColors.surfaceRaised,
    ) {
        WeeklyChart(weeks = snapshot.weeks, unit = unit, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Districts(snapshot: StatsSnapshot, onDistrictClick: (Long) -> Unit) {
    if (snapshot.districts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.stats_districts_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        DistrictList(districts = snapshot.districts, onDistrictClick = onDistrictClick)
    }
}

/** Small and quiet on purpose: totals of counted drives, speed without any emphasis. */
@Composable
private fun Classic(snapshot: StatsSnapshot, unit: DistanceUnit) {
    val sep = stringResource(Res.string.decimal_separator)
    val muted = RoadmaxxingTheme.extraColors.textSecondary
    val distance = stringResource(
        Res.string.stats_value_with_unit,
        formatDistanceValue(snapshot.totalDistanceM, unit, 1, sep),
        distanceUnitLabel(unit),
    )
    val totalMinutes = snapshot.totalDurationMs / 60_000L
    val time = if (totalMinutes >= 60) {
        stringResource(Res.string.card_duration_hm, (totalMinutes / 60).toString(), (totalMinutes % 60).toString())
    } else {
        stringResource(Res.string.card_duration_m, totalMinutes.toString())
    }
    val speedLabel = speedUnitLabel(unit)
    fun speed(mps: Double?) = if (mps == null) "–" else "${formatSpeedValue(mps, unit, 0, sep)} $speedLabel"

    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.stats_classic_title), style = MaterialTheme.typography.labelMedium, color = muted)
        ClassicRow(stringResource(Res.string.stats_classic_distance), distance)
        ClassicRow(stringResource(Res.string.stats_classic_time), time)
        ClassicRow(stringResource(Res.string.stats_classic_drives), snapshot.driveCount.toString())
        ClassicRow(stringResource(Res.string.stats_classic_avg_speed), speed(snapshot.avgSpeedMps))
        ClassicRow(stringResource(Res.string.stats_classic_max_speed), speed(snapshot.maxSpeedMps))
    }
}

@Composable
private fun ClassicRow(label: String, value: String) {
    val muted = RoadmaxxingTheme.extraColors.textSecondary
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = muted)
        Text(value, style = MaterialTheme.typography.labelMedium, color = muted)
    }
}
