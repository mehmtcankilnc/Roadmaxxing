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
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.map_attribution
import app.roadmaxxing.resources.map_content_description
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

/**
 * Exploration map: dark base map with driven roads painted on top.
 *
 * @param drivenRoadsGeoJson GeoJSON FeatureCollection of LineStrings; feature property "fresh": Boolean.
 * @param focus camera fits these bounds when it changes; null keeps the camera.
 * @param contentPadding area covered by overlays; fit and attribution stay outside it.
 */
@Composable
fun ExplorationMap(
    drivenRoadsGeoJson: String,
    focus: MapFocus?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val layoutDirection = LocalLayoutDirection.current
    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(Res.getUri(StylePath)),
        initialCameraPosition = InitialCamera,
    ) {
        // Re-evaluated when the GeoJSON string changes; the source is updated in place.
        val driven = rememberGeoJsonSource(GeoJsonData.JsonString(drivenRoadsGeoJson))

        // Fresh roads get a wide, translucent halo underneath the line.
        LineLayer(
            id = "driven-fresh-halo",
            source = driven,
            filter = feature["fresh"] eq const(true),
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
