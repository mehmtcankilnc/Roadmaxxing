package app.roadmaxxing.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.shared.map.DrivenRoadsProvider
import app.roadmaxxing.shared.map.GeoBounds
import app.roadmaxxing.ui.map.MapFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the map needs; the percentages come from the shared MainViewModel state. */
data class ExploreUiState(
    val drivenRoadsGeoJson: String = EMPTY_GEOJSON,
    val focus: MapFocus? = null,
    val segmentCount: Int = 0,
    /** True once the provider emitted at least once, so the empty invitation does not flash while loading. */
    val loaded: Boolean = false,
)

/**
 * Map data for the Explore tab. A separate ViewModel from MainViewModel: it owns one heavy stream (GeoJSON of
 * every driven segment) that only the map consumes, while the other tabs share MainViewModel's light state.
 */
class ExploreViewModel(private val provider: DrivenRoadsProvider) : ViewModel() {

    private val _state = MutableStateFlow(ExploreUiState())
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    /** Set once the user touched the map; from then on new drives no longer move the camera. */
    private var userTouchedMap = false

    fun onUserTouchedMap() {
        userTouchedMap = true
    }

    init {
        viewModelScope.launch {
            provider.observe()
                .catch { /* keep the last map; progress cards report their own errors */ }
                .collect { snapshot ->
                    // provinceBounds() blocks on its first call; the provider already warmed it for observe().
                    val bounds = snapshot.drivenBounds ?: withContext(Dispatchers.Default) { provider.provinceBounds() }
                    _state.update { old ->
                        ExploreUiState(
                            drivenRoadsGeoJson = snapshot.geoJson,
                            // First load always fits; later the camera only follows while the user has not touched the map.
                            focus = if (userTouchedMap && old.focus != null) old.focus else bounds?.toFocus(),
                            segmentCount = snapshot.segmentCount,
                            loaded = true,
                        )
                    }
                }
        }
    }
}

private fun GeoBounds.toFocus() = MapFocus(west = west, south = south, east = east, north = north)

private const val EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"
