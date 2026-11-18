package app.roadmaxxing.ui.privacy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.privacy_zone_back
import app.roadmaxxing.resources.privacy_zone_cancel
import app.roadmaxxing.resources.privacy_zone_delete
import app.roadmaxxing.resources.privacy_zone_delete_message
import app.roadmaxxing.resources.privacy_zone_delete_title
import app.roadmaxxing.resources.privacy_zone_edit
import app.roadmaxxing.resources.privacy_zone_editor_hint
import app.roadmaxxing.resources.privacy_zone_editor_title_add
import app.roadmaxxing.resources.privacy_zone_editor_title_edit
import app.roadmaxxing.resources.privacy_zone_kind_custom
import app.roadmaxxing.resources.privacy_zone_kind_home
import app.roadmaxxing.resources.privacy_zone_kind_work
import app.roadmaxxing.resources.privacy_zone_map_description
import app.roadmaxxing.resources.privacy_zone_name_label
import app.roadmaxxing.resources.privacy_zone_pin_description
import app.roadmaxxing.resources.privacy_zone_radius_label
import app.roadmaxxing.resources.privacy_zone_radius_value
import app.roadmaxxing.resources.privacy_zone_save
import app.roadmaxxing.resources.privacy_zone_suggestion_add
import app.roadmaxxing.resources.privacy_zone_suggestion_body
import app.roadmaxxing.resources.privacy_zone_suggestion_dismiss
import app.roadmaxxing.resources.privacy_zone_suggestion_title
import app.roadmaxxing.resources.privacy_zones_add
import app.roadmaxxing.resources.privacy_zones_empty
import app.roadmaxxing.resources.privacy_zones_explainer
import app.roadmaxxing.resources.privacy_zones_title
import app.roadmaxxing.shared.privacy.PrivacyZone
import app.roadmaxxing.shared.privacy.ZoneKind
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.ScreenClass
import app.roadmaxxing.ui.components.SecondaryText
import app.roadmaxxing.ui.currentScreenClass
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val StylePath = "files/map/roadmaxxing_dark.json"
private val Volt = Color(0xFFC8FF3D)
private val OnVolt = Color(0xFF1A2600)
private val ListWidth = 400.dp
private val CompactMapHeight = 260.dp
private val WideMapHeight = 380.dp
private const val EditorZoom = 14.0
private const val CirclePoints = 72

/**
 * Full-screen overlay for the privacy zones screen, opened from the Profile tab. Uses an opaque [Surface] inside a
 * non-platform-width [Dialog] (same pattern as the legal screen); system back closes the editor first, then the screen.
 */
@Composable
fun PrivacyZonesOverlay(onClose: () -> Unit) {
    val viewModel = koinViewModel<PrivacyZonesViewModel>()
    val state by viewModel.state.collectAsState()
    val compact = currentScreenClass() == ScreenClass.Compact
    Dialog(
        onDismissRequest = {
            if (compact && state.editor != null) viewModel.onIntent(PrivacyZonesIntent.CancelEditor) else onClose()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            PrivacyZonesScreen(state = state, onIntent = viewModel::onIntent, onBack = onClose)
        }
    }
}

@Composable
fun PrivacyZonesScreen(state: PrivacyZonesUiState, onIntent: (PrivacyZonesIntent) -> Unit, onBack: () -> Unit) {
    val screenClass = currentScreenClass()
    val editor = state.editor
    Box(modifier = Modifier.fillMaxSize()) {
        if (screenClass == ScreenClass.Compact) {
            if (editor != null) {
                ZoneEditorPanel(editor, onIntent, compact = true, modifier = Modifier.fillMaxSize())
            } else {
                ZoneListPane(state, onIntent, onBack, modifier = Modifier.fillMaxSize(), horizontalPadding = 20.dp)
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                ZoneListPane(state, onIntent, onBack, modifier = Modifier.width(ListWidth).fillMaxHeight(), horizontalPadding = 24.dp)
                Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 24.dp, top = 16.dp, bottom = 16.dp)) {
                    if (editor != null) {
                        ZoneEditorPanel(editor, onIntent, compact = false, modifier = Modifier.fillMaxSize())
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(24.dp),
                            color = RoadmaxxingTheme.extraColors.surfaceRaised,
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(32.dp)) {
                                Text(
                                    text = stringResource(Res.string.privacy_zone_editor_hint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = RoadmaxxingTheme.extraColors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
        state.pendingDelete?.let {
            AlertDialog(
                onDismissRequest = { onIntent(PrivacyZonesIntent.CancelDelete) },
                title = { Text(stringResource(Res.string.privacy_zone_delete_title)) },
                text = { Text(stringResource(Res.string.privacy_zone_delete_message)) },
                confirmButton = {
                    TextButton(onClick = { onIntent(PrivacyZonesIntent.ConfirmDelete) }) {
                        Text(stringResource(Res.string.privacy_zone_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onIntent(PrivacyZonesIntent.CancelDelete) }) {
                        Text(stringResource(Res.string.privacy_zone_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun ZoneListPane(
    state: PrivacyZonesUiState,
    onIntent: (PrivacyZonesIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    horizontalPadding: Dp,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = horizontalPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text("< " + stringResource(Res.string.privacy_zone_back)) }
        Text(
            text = stringResource(Res.string.privacy_zones_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        SecondaryText(stringResource(Res.string.privacy_zones_explainer))
        if (state.suggestion != null) SuggestionCard(onIntent)
        if (state.zones.isEmpty()) {
            SecondaryText(stringResource(Res.string.privacy_zones_empty))
        } else {
            state.zones.forEach { zone -> ZoneRow(zone, selected = state.editor?.id == zone.id, onIntent = onIntent) }
        }
        Button(
            onClick = { onIntent(PrivacyZonesIntent.AddZone) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) { Text(stringResource(Res.string.privacy_zones_add)) }
    }
}

@Composable
private fun SuggestionCard(onIntent: (PrivacyZonesIntent) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = RoadmaxxingTheme.extraColors.surfaceRaised,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.privacy_zone_suggestion_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(Res.string.privacy_zone_suggestion_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onIntent(PrivacyZonesIntent.AcceptSuggestion) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(stringResource(Res.string.privacy_zone_suggestion_add)) }
                TextButton(onClick = { onIntent(PrivacyZonesIntent.DismissSuggestion) }) {
                    Text(stringResource(Res.string.privacy_zone_suggestion_dismiss))
                }
            }
        }
    }
}

@Composable
private fun ZoneRow(zone: PrivacyZone, selected: Boolean, onIntent: (PrivacyZonesIntent) -> Unit) {
    val customName = zone.name
    val title = if (zone.kind == ZoneKind.Custom && !customName.isNullOrBlank()) customName else stringResource(zone.kind.label())
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = RoadmaxxingTheme.extraColors.surfaceRaised,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            SecondaryText(stringResource(Res.string.privacy_zone_radius_value, zone.radiusM.toString()))
            Row {
                TextButton(onClick = { onIntent(PrivacyZonesIntent.EditZone(zone)) }) {
                    Text(stringResource(Res.string.privacy_zone_edit))
                }
                TextButton(onClick = { onIntent(PrivacyZonesIntent.AskDelete(zone)) }) {
                    Text(stringResource(Res.string.privacy_zone_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun ZoneKind.label(): StringResource = when (this) {
    ZoneKind.Home -> Res.string.privacy_zone_kind_home
    ZoneKind.Work -> Res.string.privacy_zone_kind_work
    ZoneKind.Custom -> Res.string.privacy_zone_kind_custom
}

@Composable
private fun ZoneEditorPanel(
    editor: ZoneEditor,
    onIntent: (PrivacyZonesIntent) -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    // Camera centre = zone centre; kept here so Save can read the latest value.
    var center by remember(editor.id, editor.centerLat, editor.centerLon) {
        mutableStateOf(editor.centerLat to editor.centerLon)
    }
    Column(
        modifier = modifier.padding(horizontal = if (compact) 20.dp else 0.dp, vertical = if (compact) 16.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (editor.id == null) Res.string.privacy_zone_editor_title_add else Res.string.privacy_zone_editor_title_edit,
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        key(editor.id, editor.centerLat, editor.centerLon) {
            // Fixed height: resizing a live map view when the controls change leaves the pin off the camera centre.
            val mapModifier = Modifier.fillMaxWidth().height(if (compact) CompactMapHeight else WideMapHeight)
            ZoneEditorMap(
                initialLat = editor.centerLat,
                initialLon = editor.centerLon,
                radiusM = editor.radiusM,
                onCenterChanged = { lat, lon -> center = lat to lon },
                modifier = mapModifier.clip(RoundedCornerShape(24.dp)),
            )
        }
        // Controls scroll on short windows (e.g. a phone in landscape) so Save stays reachable.
        Column(
            modifier = Modifier.weight(1f, fill = true).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryText(stringResource(Res.string.privacy_zone_editor_hint))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZoneKind.entries.forEach { kind ->
                    FilterChip(
                        selected = editor.kind == kind,
                        onClick = { onIntent(PrivacyZonesIntent.SetKind(kind)) },
                        label = { Text(stringResource(kind.label())) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            if (editor.kind == ZoneKind.Custom) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { onIntent(PrivacyZonesIntent.SetName(it)) },
                    label = { Text(stringResource(Res.string.privacy_zone_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = stringResource(Res.string.privacy_zone_radius_label, editor.radiusM.toString()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val steps = (PrivacyZonesViewModel.MAX_RADIUS - PrivacyZonesViewModel.MIN_RADIUS) / PrivacyZonesViewModel.RADIUS_STEP - 1
            Slider(
                value = editor.radiusM.toFloat(),
                onValueChange = { onIntent(PrivacyZonesIntent.SetRadius(it.toInt())) },
                valueRange = PrivacyZonesViewModel.MIN_RADIUS.toFloat()..PrivacyZonesViewModel.MAX_RADIUS.toFloat(),
                steps = steps,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onIntent(PrivacyZonesIntent.CancelEditor) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(Res.string.privacy_zone_cancel))
                }
                Button(
                    onClick = { onIntent(PrivacyZonesIntent.Save(center.first, center.second)) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) { Text(stringResource(Res.string.privacy_zone_save)) }
            }
        }
    }
}

/** Map with a fixed centre pin and the zone circle drawn around the camera centre. */
@Composable
private fun ZoneEditorMap(
    initialLat: Double,
    initialLon: Double,
    radiusM: Int,
    onCenterChanged: (Double, Double) -> Unit,
    modifier: Modifier,
) {
    var circleCenter by remember { mutableStateOf(initialLat to initialLon) }
    val circleJson = circleGeoJson(circleCenter.first, circleCenter.second, radiusM.toDouble())
    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri(Res.getUri(StylePath)),
        initialCameraPosition = CameraPosition(center = Position(longitude = initialLon, latitude = initialLat), zoom = EditorZoom),
    ) {
        val source = rememberGeoJsonSource(GeoJsonData.JsonString(circleJson))
        FillLayer(id = "zone-fill", source = source, color = const(Volt), opacity = const(0.22f))
        LineLayer(id = "zone-outline", source = source, color = const(Volt), opacity = const(0.9f))
    }
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.cameraPosition.center }.collect { t ->
            circleCenter = t.latitude to t.longitude
            onCenterChanged(t.latitude, t.longitude)
        }
    }
    val description = stringResource(Res.string.privacy_zone_map_description)
    val pinDescription = stringResource(Res.string.privacy_zone_pin_description)
    Box(modifier = modifier) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            state = mapState,
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(18.dp)
                .clip(CircleShape)
                .background(Volt)
                .border(3.dp, OnVolt, CircleShape)
                .semantics { contentDescription = pinDescription },
        )
    }
}

/** GeoJSON polygon approximating a circle of [radiusM] metres around the given point. */
private fun circleGeoJson(lat: Double, lon: Double, radiusM: Double): String {
    val dLat = radiusM / 111_320.0
    val dLon = radiusM / (111_320.0 * cos(lat * PI / 180.0)).coerceAtLeast(1.0)
    val ring = StringBuilder()
    for (i in 0..CirclePoints) {
        val a = 2 * PI * i / CirclePoints
        if (i > 0) ring.append(',')
        ring.append('[').append(lon + dLon * cos(a)).append(',').append(lat + dLat * sin(a)).append(']')
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}," +
        "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[$ring]]}}]}"
}
