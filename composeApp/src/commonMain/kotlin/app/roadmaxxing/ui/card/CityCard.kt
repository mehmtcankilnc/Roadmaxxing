package app.roadmaxxing.ui.card

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.card_city_districts
import app.roadmaxxing.resources.card_city_explored
import app.roadmaxxing.resources.card_city_major
import app.roadmaxxing.resources.card_city_week
import app.roadmaxxing.resources.card_percent_leading
import app.roadmaxxing.resources.card_percent_symbol
import app.roadmaxxing.resources.card_unit_km
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.shared.summary.CityCardData
import app.roadmaxxing.theme.RoadmaxxingPalette
import app.roadmaxxing.ui.main.formatDecimal
import org.jetbrains.compose.resources.stringResource

/** Percent digits: two decimals below 1 % so small progress is visible, one decimal otherwise. */
internal fun percentDigits(percent: Double, sep: String): String =
    formatDecimal(percent.coerceIn(0.0, 100.0), if (percent < 1.0) 2 else 1, sep)

@Composable
fun CityCard(data: CityCardData, format: CardFormat, modifier: Modifier = Modifier) {
    val story = format == CardFormat.Story
    val sep = stringResource(Res.string.decimal_separator)
    val symbol = stringResource(Res.string.card_percent_symbol)
    val leading = stringResource(Res.string.card_percent_leading) == "1"
    fun pct(value: Double) = percentDigits(value, sep).let { if (leading) "$symbol$it" else "$it$symbol" }

    val stats = buildList {
        if (data.districtsTotal > 0) add(StatItem("${data.districtsCompleted}/${data.districtsTotal}", stringResource(Res.string.card_city_districts)))
        add(StatItem("${kmText(data.weekNewLengthM, sep)} ${stringResource(Res.string.card_unit_km)}", stringResource(Res.string.card_city_week)))
        add(StatItem(pct(data.percentMajor), stringResource(Res.string.card_city_major)))
    }.take(3)

    DesignCanvas(format, modifier) {
        CardBackground()
        Column(
            Modifier.fillMaxSize().padding(
                start = 72.dp, end = 72.dp,
                top = if (story) 150.dp else 72.dp, bottom = if (story) 200.dp else 72.dp,
            ),
        ) {
            Wordmark()
            Spacer(Modifier.height(if (story) 56.dp else 28.dp))
            Text(
                data.name,
                style = cardStyle(if (story) 72f else 60f, FontWeight.Bold, RoadmaxxingPalette.TextPrimary, 1.1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            CitySilhouette(data, Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                GiantNumber(pct(data.percent), if (story) 260f else 200f, 936f, RoadmaxxingPalette.Primary)
            }
            Text(
                stringResource(Res.string.card_city_explored),
                style = cardStyle(if (story) 44f else 38f, FontWeight.SemiBold, RoadmaxxingPalette.TextPrimary),
                maxLines = 1,
            )
            Spacer(Modifier.height(if (story) 48.dp else 28.dp))
            StatsRow(stats, Modifier.fillMaxWidth())
        }
    }
}

/** Province outline as a thin line; driven roads inside it in Volt green. Every city has its own shape. */
@Composable
private fun CitySilhouette(data: CityCardData, modifier: Modifier) {
    Canvas(modifier) {
        val boundaryPts = data.boundary.flatten()
        val projector = GeoProjector(boundaryPts.ifEmpty { data.driven.flatten() }, size, padding = 32f)
        if (!projector.valid) {
            drawPlaceholderRoad()
            return@Canvas
        }
        data.boundary.forEach { ring ->
            val pts = ring.map(projector::map)
            if (pts.size >= 3) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                    close()
                }
                drawPath(path, RoadmaxxingPalette.Surface)
            }
            strokePolyline(pts, RoadmaxxingPalette.TextSecondary.copy(alpha = 0.7f), 4f, closed = true)
        }
        data.driven.forEach { line ->
            val pts = line.map(projector::map)
            strokePolyline(pts, RoadmaxxingPalette.Primary.copy(alpha = 0.14f), 14f)
            strokePolyline(pts, RoadmaxxingPalette.Primary, 5f)
        }
    }
}
