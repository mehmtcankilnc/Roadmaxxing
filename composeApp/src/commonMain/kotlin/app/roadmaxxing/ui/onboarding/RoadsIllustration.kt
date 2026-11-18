package app.roadmaxxing.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.onboarding_intro_description
import app.roadmaxxing.shared.onboarding.IntroRoads
import app.roadmaxxing.theme.RoadmaxxingTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/** Total time for the whole network to light up. Each road takes [ROAD_WINDOW] of it (about 600 ms). */
private const val LIGHT_UP_MS = 1500
private const val ROAD_WINDOW = 0.4f

/** Number of west-to-east groups the roads are merged into for drawing. */
private const val GROUPS = 24

/**
 * Drives the intro animation: 0 = every road gray, 1 = every road Volt green. Starts once [roads] has loaded and
 * runs a single time, so later steps keep showing the green network.
 */
@Composable
fun rememberRoadsProgress(roads: IntroRoads?): Animatable<Float, *> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(roads != null) {
        if (roads != null && progress.value == 0f) {
            progress.animateTo(1f, tween(durationMillis = LIGHT_UP_MS, easing = LinearEasing))
        }
    }
    return progress
}

/**
 * Gray roads that light up to Volt green one after another. [roads] null means still loading: the card stays
 * empty (calm placeholder) instead of showing a spinner.
 */
@Composable
fun RoadsIllustration(roads: IntroRoads?, progress: () -> Float, modifier: Modifier = Modifier) {
    val gray = RoadmaxxingTheme.extraColors.roadUnexplored
    val green = MaterialTheme.colorScheme.primary
    val description = stringResource(Res.string.onboarding_intro_description)
    Surface(
        modifier = modifier.semantics { contentDescription = description },
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
            if (roads != null && roads.roads.isNotEmpty()) {
                Canvas(
                    modifier = Modifier.fillMaxSize().clipToBounds().drawWithCache {
                        // Geometry is built once per size: roads are merged into a few groups, ordered west to
                        // east, so the network lights up as a sweep and each frame only draws GROUPS paths.
                        val spanLat = (roads.north - roads.south).coerceAtLeast(1e-9)
                        val cosLat = cos((roads.north + roads.south) / 2.0 * PI / 180.0)
                        val spanX = (roads.east - roads.west).coerceAtLeast(1e-9) * cosLat
                        // Cover: fill the whole card, keep the aspect ratio and let the overflow be clipped.
                        val scale = max(size.width / spanX, size.height / spanLat)
                        val offsetX = (size.width - spanX * scale) / 2.0
                        val offsetY = (size.height - spanLat * scale) / 2.0
                        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val paths = List(GROUPS) { Path() }
                        for (road in roads.roads) {
                            if (road.size < 2) continue
                            val meanLon = road.sumOf { it.lon } / road.size
                            val group = (((meanLon - roads.west) / (roads.east - roads.west).coerceAtLeast(1e-9)) * GROUPS)
                                .toInt().coerceIn(0, GROUPS - 1)
                            val path = paths[group]
                            road.forEachIndexed { i, point ->
                                val x = (offsetX + (point.lon - roads.west) * cosLat * scale).toFloat()
                                val y = (offsetY + (roads.north - point.lat) * scale).toFloat()
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                        }
                        onDrawBehind {
                            val p = progress()
                            paths.forEachIndexed { index, path ->
                                // Group i starts at i/GROUPS of the first 60% of the timeline and takes ROAD_WINDOW.
                                val start = index.toFloat() / GROUPS * (1f - ROAD_WINDOW)
                                val t = ((p - start) / ROAD_WINDOW).coerceIn(0f, 1f)
                                drawPath(path, lerp(gray, green, t), style = stroke)
                            }
                        }
                    },
                ) {}
            }
        }
    }
}
