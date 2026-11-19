package app.roadmaxxing.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.districts_completed_badge
import app.roadmaxxing.resources.districts_row_description
import app.roadmaxxing.resources.districts_row_description_completed
import app.roadmaxxing.resources.districts_secondary_line
import app.roadmaxxing.resources.districts_sort_completion
import app.roadmaxxing.resources.districts_sort_label
import app.roadmaxxing.resources.districts_sort_name
import app.roadmaxxing.resources.percent_value
import app.roadmaxxing.shared.stats.DistrictStat
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.components.PERCENT_DECIMALS
import app.roadmaxxing.ui.main.formatDecimal
import org.jetbrains.compose.resources.stringResource

/** How the district list is ordered. Kept for the process lifetime so it survives tab switches. */
private enum class DistrictSort { Completion, Name }

private var rememberedSort by mutableStateOf(DistrictSort.Completion)

/** Text on the Volt green (docs/DESIGN.md: text on green is always dark). */
private val OnVolt = Color(0xFF1A2600)

/** Smallest bar fill (fraction of the track) for a non-zero percentage, so tiny progress stays visible. */
private const val MIN_VISIBLE_FRACTION = 0.02f

/**
 * Districts as a sortable list. Plain Column on purpose: the Stats tab already scrolls, a lazy list would nest.
 */
@Composable
fun DistrictList(
    districts: List<DistrictStat>,
    onDistrictClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sort = rememberedSort
    val sorted = remember(districts, sort) {
        when (sort) {
            DistrictSort.Completion -> districts.sortedWith(
                compareByDescending<DistrictStat> { it.percent }.thenBy { it.name.lowercase() },
            )
            DistrictSort.Name -> districts.sortedBy { it.name.lowercase() }
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val sortLabel = stringResource(Res.string.districts_sort_label)
        Row(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = sortLabel },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortChip(stringResource(Res.string.districts_sort_completion), sort == DistrictSort.Completion) {
                rememberedSort = DistrictSort.Completion
            }
            SortChip(stringResource(Res.string.districts_sort_name), sort == DistrictSort.Name) {
                rememberedSort = DistrictSort.Name
            }
        }
        sorted.forEach { DistrictRow(it, onDistrictClick) }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = OnVolt,
            labelColor = RoadmaxxingTheme.extraColors.textSecondary,
        ),
    )
}

@Composable
private fun DistrictRow(district: DistrictStat, onClick: (Long) -> Unit) {
    val sep = stringResource(Res.string.decimal_separator)
    val percentText = stringResource(Res.string.percent_value, formatDecimal(district.percent, PERCENT_DECIMALS, sep))
    val description = stringResource(
        if (district.completed) Res.string.districts_row_description_completed else Res.string.districts_row_description,
        district.name,
        percentText,
    )
    val secondary = stringResource(
        Res.string.districts_secondary_line,
        formatDecimal(district.percentMajor, PERCENT_DECIMALS, sep),
        formatDecimal(district.percentLocal, PERCENT_DECIMALS, sep),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { onClick(district.regionId) }
            .clearAndSetSemantics { contentDescription = description; role = Role.Button }
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = district.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (district.completed) CompletedBadge()
            Text(
                text = percentText,
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
                color = if (district.percent > 0.0) MaterialTheme.colorScheme.primary else RoadmaxxingTheme.extraColors.textSecondary,
            )
        }
        ThinBar(district.percent)
        Text(
            text = secondary,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = RoadmaxxingTheme.extraColors.textSecondary,
        )
    }
}

@Composable
private fun CompletedBadge() {
    Text(
        text = stringResource(Res.string.districts_completed_badge),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = OnVolt,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A 4 dp bar: Volt fill on a muted track; a non-zero percentage never renders thinner than [MIN_VISIBLE_FRACTION]. */
@Composable
private fun ThinBar(percent: Double) {
    val raw = (percent / 100.0).toFloat().coerceIn(0f, 1f)
    val fraction = if (raw > 0f) maxOf(raw, MIN_VISIBLE_FRACTION) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(50))
            .background(RoadmaxxingTheme.extraColors.roadUnexplored),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
