package app.roadmaxxing.ui.card

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.app_name
import app.roadmaxxing.shared.summary.GeoPoint
import app.roadmaxxing.theme.RoadmaxxingPalette
import app.roadmaxxing.theme.interFontFamily
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val DEG_TO_RAD = 0.017453292519943295

/**
 * Lays [content] out at the fixed design size of [format] (1 dp == 1 sp == 1 px) and scales it to the size the
 * parent gives, keeping the aspect ratio. With a fixed `widthPx x heightPx` constraint the scale is exactly 1.
 */
@Composable
internal fun DesignCanvas(format: CardFormat, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Layout(
        content = {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                Box(Modifier.fillMaxSize().background(RoadmaxxingPalette.Background).clipToBounds()) { content() }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val ratio = format.widthPx.toFloat() / format.heightPx
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else format.widthPx
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val w = min(maxW.toLong(), (maxH * ratio.toDouble()).toLong().coerceAtLeast(1)).toInt()
        val h = if (w == format.widthPx) format.heightPx else (w / ratio).roundToInt()
        val scale = w / format.widthPx.toFloat()
        val placeable = measurables.first().measure(Constraints.fixed(format.widthPx, format.heightPx))
        layout(w, h) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** Dark ground with a soft Volt glow in the top-right corner. */
@Composable
internal fun BoxScope.CardBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                colors = listOf(RoadmaxxingPalette.Primary.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(size.width * 0.95f, size.height * 0.04f),
                radius = size.width * 0.9f,
            ),
        )
    }
}

/**
 * The single source of the Roadmaxxing wordmark on cards. Swap the body when the real logo arrives.
 * Sizes are design pixels (cards are laid out at density 1).
 */
@Composable
internal fun Wordmark(modifier: Modifier = Modifier, scale: Float = 1f) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size((56 * scale).dp)) {
            drawRoundRect(RoadmaxxingPalette.Primary, cornerRadius = CornerRadius(size.width * 0.28f))
            val path = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.78f)
                cubicTo(
                    size.width * 0.28f, size.height * 0.45f, size.width * 0.72f, size.height * 0.60f,
                    size.width * 0.72f, size.height * 0.22f,
                )
            }
            drawPath(
                path, RoadmaxxingPalette.OnPrimary,
                style = Stroke(size.width * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        Spacer(Modifier.width((16 * scale).dp))
        Text(
            text = stringResource(Res.string.app_name),
            style = cardStyle(44f * scale, FontWeight.SemiBold, RoadmaxxingPalette.TextPrimary),
            maxLines = 1,
        )
    }
}

@Composable
internal fun cardStyle(sizeSp: Float, weight: FontWeight, color: Color, lineHeightFactor: Float = 1.2f): TextStyle =
    TextStyle(
        fontFamily = interFontFamily(),
        fontWeight = weight,
        fontSize = sizeSp.sp,
        lineHeight = (sizeSp * lineHeightFactor).sp,
        color = color,
        fontFeatureSettings = "tnum",
    )

/** Giant number that shrinks with its length so it never overflows the card width. */
@Composable
internal fun GiantNumber(text: String, baseSizeSp: Float, availableWidth: Float, color: Color) {
    val size = min(baseSizeSp, availableWidth / (max(text.length, 1) * 0.66f))
    Text(
        text = text,
        style = cardStyle(size, FontWeight.Bold, color, lineHeightFactor = 1.05f),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

internal class StatItem(val value: String, val label: String)

@Composable
internal fun StatsRow(stats: List<StatItem>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(32.dp)) {
        stats.forEach { Stat(it) }
    }
}

@Composable
private fun RowScope.Stat(item: StatItem) {
    Column(Modifier.weight(1f)) {
        val long = item.value.length > 11
        Text(
            text = item.value,
            style = cardStyle(if (long) 40f else 56f, FontWeight.SemiBold, RoadmaxxingPalette.TextPrimary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.label,
            style = cardStyle(28f, FontWeight.Medium, RoadmaxxingPalette.TextSecondary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
    }
}

/** Fits geo points into a pixel rectangle with an equirectangular projection (cos-latitude corrected). */
internal class GeoProjector(points: List<GeoPoint>, private val size: Size, padding: Float) {
    private val minX: Double
    private val minY: Double
    private val scale: Double
    private val offX: Double
    private val offY: Double
    val valid: Boolean

    init {
        if (points.isEmpty()) {
            minX = 0.0; minY = 0.0; scale = 1.0; offX = 0.0; offY = 0.0; valid = false
        } else {
            val midLat = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2.0
            val k = cos(midLat * DEG_TO_RAD)
            val xs = points.map { it.lon * k }
            val ys = points.map { -it.lat }
            minX = xs.min(); minY = ys.min()
            val dx = xs.max() - minX
            val dy = ys.max() - minY
            val availW = (size.width - 2 * padding).toDouble()
            val availH = (size.height - 2 * padding).toDouble()
            valid = dx > 0.0 || dy > 0.0
            scale = if (!valid) 1.0 else min(if (dx > 0) availW / dx else Double.MAX_VALUE, if (dy > 0) availH / dy else Double.MAX_VALUE)
            offX = padding + (availW - dx * scale) / 2.0
            offY = padding + (availH - dy * scale) / 2.0
        }
    }

    private val cosLat = if (points.isEmpty()) 1.0 else cos((points.minOf { it.lat } + points.maxOf { it.lat }) / 2.0 * DEG_TO_RAD)

    fun map(p: GeoPoint): Offset =
        Offset((offX + (p.lon * cosLat - minX) * scale).toFloat(), (offY + (-p.lat - minY) * scale).toFloat())
}

internal fun DrawScope.strokePolyline(points: List<Offset>, color: Color, width: Float, closed: Boolean = false) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        if (closed) close()
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Stylised "road ahead" drawing for cards that have no geometry to show. */
internal fun DrawScope.drawPlaceholderRoad() {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.15f, h * 0.85f)
        cubicTo(w * 0.15f, h * 0.45f, w * 0.85f, h * 0.65f, w * 0.85f, h * 0.15f)
    }
    drawPath(
        path, RoadmaxxingPalette.RoadUnexplored,
        style = Stroke(10f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(28f, 26f))),
    )
    val end = Offset(w * 0.85f, h * 0.15f)
    drawCircle(RoadmaxxingPalette.Primary.copy(alpha = 0.18f), radius = 44f, center = end)
    drawCircle(RoadmaxxingPalette.Primary, radius = 20f, center = end)
}
