package app.roadmaxxing.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.roadmaxxing.platform.rememberReduceMotion
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.map_attribution
import app.roadmaxxing.resources.map_content_description
import app.roadmaxxing.ui.explore.BurnPlayback
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.camera.CameraFit
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.exponential
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.interpolate
import org.maplibre.compose.expressions.dsl.zoom
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position

/** Geographic bounds (WGS84 degrees) the camera should fit. */
data class MapFocus(val west: Double, val south: Double, val east: Double, val north: Double)

// Design tokens (docs/DESIGN.md): primary = explored road.
private val Explored = Color(0xFFC8FF3D)
private val ExploredHalo = Color(0xFFC8FF3D)
private val AttributionColor = Color(0xFF8A8D94)
// surface token (#16171B) at 70% opacity.
private val AttributionBackground = Color(0xFF16171B).copy(alpha = 0.7f)

private const val StylePath = "files/map/roadmaxxing_dark.json"
private val FitMargin = 24.dp
private val AttributionInset = 6.dp

// Adana city centre; only used until the first focus arrives.
private val InitialCamera = CameraPosition(center = Position(longitude = 35.3213, latitude = 37.0), zoom = 10.0)

private const val EmptyGeoJson = "{\"type\":\"FeatureCollection\",\"features\":[]}"
private const val FitSettleMs = 250L
private const val MinFrameIntervalNs = 25_000_000L

/** Gentle ease-in-out: halfway between linear and smoothstep, so the front still reads as travelling. */
private fun ease(t: Double): Double = 0.5 * t + 0.5 * (t * t * (3.0 - 2.0 * t))

/**
 * Exploration map: dark base map with driven roads painted on top.
 *
 * @param drivenRoadsGeoJson GeoJSON FeatureCollection of LineStrings; feature property "fresh": Boolean.
 * @param focus camera fits these bounds when it changes; null keeps the camera.
 * @param contentPadding area covered by overlays; fit and attribution stay outside it.
 * @param burn new-road burn to play once (see ExploreViewModel); null when nothing is pending.
 */
@Composable
fun ExplorationMap(
    drivenRoadsGeoJson: String,
    focus: MapFocus?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    burn: BurnPlayback? = null,
) {
    // Burn animation state, read inside the style content below: [burnActive] swaps the normal fresh-road layers for
    // the burn layers, [burnJson] is the current frame of the burn source.
    var burnActive by remember { mutableStateOf(false) }
    var burnJson by remember { mutableStateOf(EmptyGeoJson) }
    val layoutDirection = LocalLayoutDirection.current
    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(Res.getUri(StylePath)),
        initialCameraPosition = InitialCamera,
    ) {
        // Re-evaluated when the GeoJSON string changes; the source is updated in place.
        val driven = rememberGeoJsonSource(GeoJsonData.JsonString(drivenRoadsGeoJson))

        // Updated in place on every frame while a burn plays; empty otherwise.
        val burnSource = rememberGeoJsonSource(GeoJsonData.JsonString(burnJson))
        val playing = burnActive

        // Fresh roads get a wide, translucent halo underneath the line (hidden while their burn plays).
        LineLayer(
            id = "driven-fresh-halo",
            source = driven,
            filter = feature["fresh"] eq const(true),
            visible = !playing,
            color = const(ExploredHalo),
            opacity = const(0.28f),
            blur = const(4.dp),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
            width = interpolate(
                type = exponential(1.5f),
                input = zoom(),
                8 to const(6.dp),
                14 to const(18.dp),
                18 to const(34.dp),
            ),
        )
        // Explored roads are wider than the grey base roads so colour is not the only signal.
        LineLayer(
            id = "driven-roads",
            source = driven,
            // While the burn plays, fresh roads are drawn by the burn layers instead.
            filter = if (playing) feature["fresh"] eq const(false) else null,
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
            color = const(Explored),
            width = interpolate(
                type = exponential(1.5f),
                input = zoom(),
                8 to const(2.5.dp),
                14 to const(6.dp),
                18 to const(14.dp),
            ),
        )
        // The burnt part of the fresh roads: same look as driven-roads.
        LineLayer(
            id = "burn-roads",
            source = burnSource,
            filter = feature["kind"] eq const("burnt"),
            cap = const(LineCap.Round),
            join = const(LineJoin.Round),
            color = const(Explored),
            width = interpolate(
                type = exponential(1.5f),
                input = zoom(),
                8 to const(2.5.dp),
                14 to const(6.dp),
                18 to const(14.dp),
            ),
        )
        // Glowing head at the burn front.
        CircleLayer(
            id = "burn-head",
            source = burnSource,
            filter = feature["kind"] eq const("head"),
            color = const(Explored),
            blur = const(0.9f),
            opacity = const(0.95f),
            radius = interpolate(
                type = exponential(1.5f),
                input = zoom(),
                8 to const(6.dp),
                14 to const(12.dp),
                18 to const(22.dp),
            ),
        )
    }

    val padding = DpPadding(
        left = contentPadding.calculateLeftPadding(layoutDirection),
        top = contentPadding.calculateTopPadding(),
        right = contentPadding.calculateRightPadding(layoutDirection),
        bottom = contentPadding.calculateBottomPadding(),
    )
    val currentPadding by rememberUpdatedState(padding)

    LaunchedEffect(focus, mapState) {
        val f = focus ?: return@LaunchedEffect
        mapState.fitCameraToBounds(
            boundingBox = BoundingBox(west = f.west, south = f.south, east = f.east, north = f.north),
            fit = CameraFit(
                fitPadding = DpPadding(FitMargin, FitMargin, FitMargin, FitMargin),
            ),
        )
    }

    val reduceMotion by rememberUpdatedState(rememberReduceMotion())
    LaunchedEffect(burn, mapState) {
        val b = burn ?: return@LaunchedEffect
        // Reduce motion: no animation, the end state is already what the normal layers show. markShown was done.
        if (reduceMotion) {
            b.onFinished()
            return@LaunchedEffect
        }
        // Hide the fresh roads right away so they do not show for a moment before burning.
        burnActive = true
        try {
            val fit = b.fitTo
            if (fit != null) {
                mapState.fitCameraToBounds(
                    boundingBox = BoundingBox(west = fit.west, south = fit.south, east = fit.east, north = fit.north),
                    fit = CameraFit(fitPadding = DpPadding(FitMargin, FitMargin, FitMargin, FitMargin)),
                )
                delay(FitSettleMs)
            }
            val duration = b.timeline.durationMs.toDouble()
            val start = withFrameNanos { it }
            var lastUpdate = start
            while (true) {
                val now = withFrameNanos { it }
                val t = ((now - start) / 1_000_000.0 / duration).coerceIn(0.0, 1.0)
                if (t >= 1.0) break
                // Throttled: the source re-parses the whole frame, so cap the update rate.
                if (now - lastUpdate >= MinFrameIntervalNs) {
                    lastUpdate = now
                    burnJson = b.timeline.frame(ease(t).toFloat())
                }
            }
            burnJson = b.timeline.frame(1f)
            // Let the finished burn render one frame before the normal layers take over.
            withFrameNanos { }
        } finally {
            burnActive = false
            burnJson = EmptyGeoJson
            b.onFinished()
        }
    }

    val description = stringResource(Res.string.map_content_description)
    MaplibreMap(
        modifier = modifier.semantics { contentDescription = description },
        state = mapState,
        viewportInsets = padding,
    ) {
        // Attribution is always visible, clear of the overlays covered by contentPadding.
        Box(Modifier.fillMaxSize()) {
            Text(
                text = stringResource(Res.string.map_attribution),
                color = AttributionColor,
                fontSize = 10.sp,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = AttributionInset + currentPadding.right,
                        bottom = AttributionInset + currentPadding.bottom,
                    )
                    .background(AttributionBackground, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
