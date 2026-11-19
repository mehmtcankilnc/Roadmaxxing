package app.roadmaxxing.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.stats_chart_description
import app.roadmaxxing.resources.stats_chart_empty
import app.roadmaxxing.resources.stats_chart_this_week
import app.roadmaxxing.resources.stats_chart_title
import app.roadmaxxing.resources.stats_chart_week_value
import app.roadmaxxing.resources.stats_months_short
import app.roadmaxxing.shared.settings.DistanceUnit
import app.roadmaxxing.shared.stats.WeekBar
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.main.distanceUnitLabel
import app.roadmaxxing.ui.main.formatDistanceValue
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

private val ChartHeight = 120.dp
private val MinBarHeight = 4.dp

/**
 * Bar chart of new road per week (oldest to newest; the last bar is the current week, in Volt green).
 * Tap a bar to read its value; the current week is shown by default. Drawn with Canvas, no chart library.
 */
@Composable
fun WeeklyChart(weeks: List<WeekBar>, unit: DistanceUnit, modifier: Modifier = Modifier) {
    val sep = stringResource(Res.string.decimal_separator)
    val unitLabel = distanceUnitLabel(unit)
    val months = stringArrayResource(Res.array.stats_months_short)
    fun label(bar: WeekBar): String {
        val date = LocalDate.fromEpochDays(bar.weekStartEpochDay)
        return "${date.day} ${months[date.month.ordinal]}"
    }
    fun value(bar: WeekBar) = formatDistanceValue(bar.newMeters, unit, 1, sep)

    var selected by remember(weeks.size) { mutableStateOf(weeks.lastIndex) }
    val summary = weeks.joinToString("; ") { "${label(it)}: ${value(it)} $unitLabel" }
    val description = stringResource(Res.string.stats_chart_description, summary)
    val allZero = weeks.none { it.newMeters > 0.0 }
    val max = weeks.maxOfOrNull { it.newMeters }?.takeIf { it > 0.0 } ?: 1.0
    val primary = MaterialTheme.colorScheme.primary
    val muted = RoadmaxxingTheme.extraColors.roadUnexplored
    val mutedSelected = RoadmaxxingTheme.extraColors.textSecondary

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(Res.string.stats_chart_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        weeks.getOrNull(selected)?.let { bar ->
            Text(
                text = if (selected == weeks.lastIndex) {
                    stringResource(Res.string.stats_chart_this_week, value(bar), unitLabel)
                } else {
                    stringResource(Res.string.stats_chart_week_value, label(bar), value(bar), unitLabel)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            weeks.forEachIndexed { index, week ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ChartHeight)
                            .clearAndSetSemantics {}
                            .clickable { selected = index },
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val barH = (size.height * (week.newMeters / max).toFloat()).coerceAtLeast(MinBarHeight.toPx())
                            val color = when {
                                index == weeks.lastIndex -> primary
                                index == selected -> mutedSelected
                                else -> muted
                            }
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(0f, size.height - barH),
                                size = Size(size.width, barH),
                                cornerRadius = CornerRadius(6.dp.toPx()),
                            )
                        }
                    }
                    Text(
                        text = label(week),
                        style = MaterialTheme.typography.labelMedium,
                        fontSize = 10.sp,
                        color = if (index == selected) MaterialTheme.colorScheme.onSurface else RoadmaxxingTheme.extraColors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
        if (allZero) {
            Text(
                stringResource(Res.string.stats_chart_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = RoadmaxxingTheme.extraColors.textSecondary,
            )
        }
    }
}
