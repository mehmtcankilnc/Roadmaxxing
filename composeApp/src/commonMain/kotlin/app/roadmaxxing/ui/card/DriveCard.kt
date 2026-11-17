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
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.card_duration_hm
import app.roadmaxxing.resources.card_duration_m
import app.roadmaxxing.resources.card_label_driven
import app.roadmaxxing.resources.card_label_new_road
import app.roadmaxxing.resources.card_stat_distance
import app.roadmaxxing.resources.card_stat_duration
import app.roadmaxxing.resources.card_stat_region
import app.roadmaxxing.resources.card_stat_speed
import app.roadmaxxing.resources.card_unit_km
import app.roadmaxxing.resources.card_zero_new
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.shared.summary.CardPolyline
import app.roadmaxxing.shared.summary.DriveCardData
import app.roadmaxxing.theme.RoadmaxxingPalette
import app.roadmaxxing.ui.main.formatDecimal
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Kilometres with one decimal, two when the value is tiny so it never reads "0,0". */
internal fun kmText(meters: Double, sep: String): String {
    val km = meters / 1000.0
    return formatDecimal(km, if (km in 0.0001..0.0999) 2 else 1, sep)
}

/** Numeric date (dd.MM.yyyy) in the device time zone; identical in Turkish and English. */
@OptIn(ExperimentalTime::class)
internal fun cardDateText(epochMs: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${two(t.day)}.${two(t.month.ordinal + 1)}.${t.year}"
}

@Composable
fun DriveCard(data: DriveCardData, format: CardFormat, hideSpeed: Boolean, modifier: Modifier = Modifier) {
    val story = format == CardFormat.Story
    val sep = stringResource(Res.string.decimal_separator)
    val unitKm = stringResource(Res.string.card_unit_km)
    val hasNew = data.newLengthM >= 1.0
    val heroValue = kmText(if (hasNew) data.newLengthM else data.distanceM, sep)
    val heroLabel = stringResource(if (hasNew) Res.string.card_label_new_road else Res.string.card_label_driven)

    val minutes = (data.durationMs / 60_000.0).roundToLong()
    val duration = if (minutes >= 60) {
        stringResource(Res.string.card_duration_hm, (minutes / 60).toString(), (minutes % 60).toString())
    } else {
        stringResource(Res.string.card_duration_m, minutes.toString())
    }
    val distanceStat = StatItem("${kmText(data.distanceM, sep)} $unitKm", stringResource(Res.string.card_stat_distance))
    val durationStat = StatItem(duration, stringResource(Res.string.card_stat_duration))
    val speedStat = if (hideSpeed) null else data.avgSpeedMps?.let { avg ->
        val a = (avg * 3.6).roundToLong().toString()
        val m = data.maxSpeedMps?.let { (it * 3.6).roundToLong().toString() }
        StatItem(if (m != null) "$a / $m" else a, stringResource(Res.string.card_stat_speed))
    }
    val regionStat = data.regionName?.takeIf { it.isNotBlank() }?.let { StatItem(it, stringResource(Res.string.card_stat_region)) }
    // Max three supporting stats; the hero already shows the distance when there is no new road.
    val stats = (if (hasNew) listOf(distanceStat, durationStat, speedStat ?: regionStat) else listOf(durationStat, speedStat, regionStat))
        .filterNotNull().take(3)

    DesignCanvas(format, modifier) {
        CardBackground()
        Column(
            Modifier.fillMaxSize().padding(
                start = 72.dp, end = 72.dp,
                top = if (story) 150.dp else 72.dp, bottom = if (story) 200.dp else 72.dp,
            ),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(Modifier.weight(1f))
                Text(
                    cardDateText(data.endMs),
                    style = cardStyle(32f, FontWeight.Medium, RoadmaxxingPalette.TextSecondary),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(if (story) 64.dp else 32.dp))
            GiantNumber(heroValue, if (story) 300f else 220f, 936f, RoadmaxxingPalette.Primary)
            Text(
                heroLabel,
                style = cardStyle(if (story) 44f else 38f, FontWeight.SemiBold, RoadmaxxingPalette.TextPrimary),
                maxLines = 1,
            )
            if (!hasNew) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.card_zero_new),
                    style = cardStyle(32f, FontWeight.Medium, RoadmaxxingPalette.TextSecondary),
                    maxLines = 2,
                )
            }
            RouteArt(data.route, Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp))
            StatsRow(stats, Modifier.fillMaxWidth())
        }
    }
}

/** Route drawing: fresh stretches thick Volt green with a soft glow, the rest pale and thin. */
@Composable
internal fun RouteArt(route: List<CardPolyline>, modifier: Modifier) {
    Canvas(modifier) {
        val all = route.flatMap { it.points }
        val projector = GeoProjector(all, size, padding = 40f)
        if (all.size < 2 || !projector.valid) {
            drawPlaceholderRoad()
            return@Canvas
        }
        route.filter { !it.fresh }.forEach { line ->
            strokePolyline(line.points.map(projector::map), RoadmaxxingPalette.TextSecondary.copy(alpha = 0.45f), 7f)
        }
        route.filter { it.fresh }.forEach { line ->
            val pts = line.points.map(projector::map)
            strokePolyline(pts, RoadmaxxingPalette.Primary.copy(alpha = 0.16f), 36f)
            strokePolyline(pts, RoadmaxxingPalette.Primary, 15f)
        }
    }
}
