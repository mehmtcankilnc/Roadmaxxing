package app.roadmaxxing.ui.card

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.card_milestone_district_done
import app.roadmaxxing.resources.card_milestone_districts_done
import app.roadmaxxing.resources.card_milestone_plus_district
import app.roadmaxxing.resources.card_milestone_plus_districts
import app.roadmaxxing.resources.card_milestone_province
import app.roadmaxxing.resources.card_percent_leading
import app.roadmaxxing.resources.card_percent_symbol
import app.roadmaxxing.shared.milestones.Celebration
import app.roadmaxxing.shared.summary.CityCardData
import app.roadmaxxing.theme.RoadmaxxingPalette
import org.jetbrains.compose.resources.stringResource

/** City data (already privacy-clipped by the summary repository) plus the milestone(s) reached in one drive. */
data class MilestoneCardData(val city: CityCardData, val celebration: Celebration)

/**
 * The city card with a milestone headline: province threshold ("5 %" + "of Adana explored"), completed
 * districts only ("Seyhan completed"), or both (province headline plus a "+ Seyhan completed" line).
 */
@Composable
fun MilestoneCard(data: MilestoneCardData, format: CardFormat, modifier: Modifier = Modifier) {
    val province = data.celebration.province
    val districts = data.celebration.districts
    CityCardScaffold(data.city, format, modifier) { _, story ->
        val primary = RoadmaxxingPalette.Primary
        val ink = RoadmaxxingPalette.TextPrimary
        if (province != null) {
            val symbol = stringResource(Res.string.card_percent_symbol)
            val leading = stringResource(Res.string.card_percent_leading) == "1"
            val number = province.percent.toString().let { if (leading) "$symbol$it" else "$it$symbol" }
            Row(verticalAlignment = Alignment.Bottom) { GiantNumber(number, if (story) 260f else 200f, 936f, primary) }
            Text(
                stringResource(Res.string.card_milestone_province, province.name),
                style = cardStyle(if (story) 44f else 38f, FontWeight.SemiBold, ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (districts.isNotEmpty()) {
                Spacer(Modifier.height(if (story) 24.dp else 14.dp))
                val line = if (districts.size == 1) {
                    stringResource(Res.string.card_milestone_plus_district, districts.first().name)
                } else {
                    stringResource(Res.string.card_milestone_plus_districts, districts.size.toString())
                }
                Text(
                    line,
                    style = cardStyle(if (story) 40f else 34f, FontWeight.Bold, primary),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (districts.size == 1) {
            GiantNumber(districts.first().name, if (story) 150f else 120f, 936f, primary)
            Text(
                stringResource(Res.string.card_milestone_district_done),
                style = cardStyle(if (story) 44f else 38f, FontWeight.SemiBold, ink),
                maxLines = 1,
            )
        } else {
            GiantNumber(districts.size.toString(), if (story) 260f else 200f, 936f, primary)
            Text(
                stringResource(Res.string.card_milestone_districts_done),
                style = cardStyle(if (story) 44f else 38f, FontWeight.SemiBold, ink),
                maxLines = 1,
            )
        }
    }
}
