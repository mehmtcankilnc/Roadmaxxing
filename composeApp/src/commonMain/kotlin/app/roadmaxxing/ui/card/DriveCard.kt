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
import androidx.compose.ui.geometry.Offset
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
fun DriveCard(
    data: DriveCardData,
    format: CardFormat,
    hideSpeed: Boolean,
    modifier: Modifier = Modifier,
    /** 0..1 share of the route drawn (on-screen preview animation); 1f is the final card, as exported. */
    routeProgress: Float = 1f,
) {
    val story = format == CardFormat.Story
    val sep = stringResource(Res.string.decimal_separator)
    val unitKm = stringResource(Res.string.card_unit_km)
    val hasNew = data.newLengthM >= 1.0
    val heroMeters = if (hasNew) data.newLengthM else data.distanceM
    val heroValue = kmText(if (routeProgress >= 1f) heroMeters else heroMeters * routeProgress.coerceIn(0f, 1f), sep)
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
            RouteArt(data.route, Modifier.weight(1f).fillMaxWidth().padding(vertical = 24.dp), routeProgress)
            StatsRow(stats, Modifier.fillMaxWidth())
        }
    }
}

/**
 * Route drawing: fresh stretches thick Volt green with a soft glow, the rest pale and thin.
 * [progress] (0..1) draws only the first part of the drive, with a glowing head dot while 0 < progress < 1.
 * At 1f (the default, used by the PNG renderer) output is the full route, drawn exactly as before.
 */
@Composable
internal fun RouteArt(route: List<CardPolyline>, modifier: Modifier, progress: Float = 1f) {
    Canvas(modifier) {
        val all = route.flatMap { it.points }
        val projector = GeoProjector(all, size, padding = 40f)
        if (all.size < 2 || !projector.valid) {
            drawPlaceholderRoad()
            return@Canvas
        }
        val full = route.map { line -> line.points.map(projector::map) }
        val p = progress.coerceIn(0f, 1f)
        val partial = if (p >= 1f) null else partialRoute(route, full, p)
        val shown: List<List<List<Offset>>> = partial?.runs ?: full.map { listOf(it) }
        route.forEachIndexed { i, line ->
            if (!line.fresh) shown[i].forEach { strokePolyline(it, RoadmaxxingPalette.TextSecondary.copy(alpha = 0.45f), 7f) }
        }
        route.forEachIndexed { i, line ->
            if (line.fresh) shown[i].forEach { pts ->
                strokePolyline(pts, RoadmaxxingPalette.Primary.copy(alpha = 0.16f), 36f)
                strokePolyline(pts, RoadmaxxingPalette.Primary, 15f)
            }
        }
        val head = partial?.head
        if (p > 0f && head != null) {
            drawCircle(RoadmaxxingPalette.Primary.copy(alpha = 0.25f), radius = 44f, center = head)
            drawCircle(RoadmaxxingPalette.Primary, radius = 20f, center = head)
        }
    }
}

private class PartialRoute(val runs: List<List<List<Offset>>>, val head: Offset?)

private fun pathLength(pts: List<Offset>): Float {
    var s = 0f
    for (i in 1 until pts.size) s += (pts[i] - pts[i - 1]).getDistance()
    return s
}

/** The first [fraction] of [pts] by length, plus the point where it ends. */
private fun cutByLength(pts: List<Offset>, fraction: Float): Pair<List<Offset>, Offset?> {
    if (pts.size < 2) return emptyList<Offset>() to null
    var remaining = pathLength(pts) * fraction
    val out = ArrayList<Offset>(pts.size)
    out.add(pts[0])
    for (i in 1 until pts.size) {
        val seg = (pts[i] - pts[i - 1]).getDistance()
        if (seg <= remaining) {
            out.add(pts[i]); remaining -= seg
        } else {
            val t = if (seg > 0f) remaining / seg else 0f
            out.add(pts[i - 1] + (pts[i] - pts[i - 1]) * t)
            break
        }
    }
    return out to out.last()
}

/**
 * Cards carry the whole drive as the first (pale) polyline and the new-road pieces on top of it, in no
 * particular order. The front moves along the drive line; a new-road piece shows up as far as the front
 * has passed the spot where it touches the drive line.
 */
private fun partialRoute(route: List<CardPolyline>, full: List<List<Offset>>, fraction: Float): PartialRoute {
    val baseIdx = route.indexOfFirst { !it.fresh }
    if (baseIdx < 0) {
        // Only new-road pieces: reveal them one after the other by length.
        var remaining = full.sumOf { pathLength(it).toDouble() }.toFloat() * fraction
        var head: Offset? = null
        val runs = full.map { pts ->
            val len = pathLength(pts)
            if (remaining <= 0f || pts.size < 2) return@map emptyList<List<Offset>>()
            if (len <= remaining) {
                remaining -= len; head = pts.last(); listOf(pts)
            } else {
                val (cut, end) = cutByLength(pts, remaining / len)
                remaining = 0f; head = end; listOf(cut)
            }
        }
        return PartialRoute(runs, head)
    }
    val base = full[baseIdx]
    val (baseCut, head) = cutByLength(base, fraction)
    val cum = FloatArray(base.size)
    for (i in 1 until base.size) cum[i] = cum[i - 1] + (base[i] - base[i - 1]).getDistance()
    val limit = cum.last() * fraction
    val runs = route.indices.map { i ->
        when {
            i == baseIdx -> listOf(baseCut)
            !route[i].fresh -> emptyList()
            else -> {
                val result = ArrayList<List<Offset>>()
                var current = ArrayList<Offset>()
                for (pt in full[i]) {
                    var best = 0
                    var bestD = Float.MAX_VALUE
                    for (k in base.indices) {
                        val d = (base[k] - pt).getDistanceSquared()
                        if (d < bestD) { bestD = d; best = k }
                    }
                    if (cum[best] <= limit) {
                        current.add(pt)
                    } else if (current.isNotEmpty()) {
                        result.add(current); current = ArrayList()
                    }
                }
                if (current.isNotEmpty()) result.add(current)
                result.filter { it.size >= 2 }
            }
        }
    }
    return PartialRoute(runs, head)
}
