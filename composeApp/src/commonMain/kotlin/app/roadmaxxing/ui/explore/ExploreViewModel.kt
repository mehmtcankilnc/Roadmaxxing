package app.roadmaxxing.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.shared.map.DrivenRoadsProvider
import app.roadmaxxing.shared.map.GeoBounds
import app.roadmaxxing.shared.map.burn.BurnRepository
import app.roadmaxxing.shared.map.burn.BurnTimeline
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
    /** The new-road burn to play on the map right now; null when nothing is pending or it already finished. */
    val burn: BurnPlayback? = null,
)

/**
 * One burn animation request. [fitTo] is set while the camera may still move (the user has not touched the map):
 * the map fits it first and starts the burn once the fit settled. [onFinished] clears the request.
 */
class BurnPlayback(
    val timeline: BurnTimeline,
    val fitTo: MapFocus?,
    val onFinished: () -> Unit,
)

/**
 * Map data for the Explore tab. A separate ViewModel from MainViewModel: it owns one heavy stream (GeoJSON of
 * every driven segment) that only the map consumes, while the other tabs share MainViewModel's light state.
 */
class ExploreViewModel(
    private val provider: DrivenRoadsProvider,
    private val burns: BurnRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ExploreUiState())
    val state: StateFlow<ExploreUiState> = _state.asStateFlow()

    /** Set once the user touched the map; from then on new drives no longer move the camera. */
    private var userTouchedMap = false

    fun onUserTouchedMap() {
        userTouchedMap = true
    }

    private var mapVisible = false
    private var burnInFlight = false

    /** The map tab (without a detail screen on top) is what the user currently sees. */
    fun onMapVisible(visible: Boolean) {
        mapVisible = visible
        if (visible) checkBurn()
    }

    /** Plays the latest drive's burn once: only when the map is visible and the driven roads are loaded. */
    private fun checkBurn() {
        if (!mapVisible || !_state.value.loaded || burnInFlight) return
        burnInFlight = true
        viewModelScope.launch {
            val timeline = try {
                withContext(Dispatchers.Default) { burns.pending() }
            } catch (_: Exception) {
                null
            }
            if (timeline == null || !mapVisible) {
                burnInFlight = false
                return@launch
            }
            // Marked at the start so a crash mid-animation never loops the burn forever.
            withContext(Dispatchers.Default) { burns.markShown(timeline.driveId) }
            val fit = if (userTouchedMap) null else timeline.bounds?.toFocus()
            val playback = BurnPlayback(timeline, fit) {
                burnInFlight = false
                _state.update { it.copy(burn = null) }
            }
            _state.update { it.copy(burn = playback) }
        }
    }

    init {
        viewModelScope.launch {
            provider.observe()
                .catch { /* keep the last map; progress cards report their own errors */ }
                .collect { snapshot ->
                    // provinceBounds() blocks on its first call; the provider already warmed it for observe().
                    val bounds = snapshot.drivenBounds ?: withContext(Dispatchers.Default) { provider.provinceBounds() }
                    _state.update { old ->
                        old.copy(
                            drivenRoadsGeoJson = snapshot.geoJson,
                            // First load always fits; later the camera only follows while the user has not touched the map.
                            focus = if (userTouchedMap && old.focus != null) old.focus else bounds?.toFocus(),
                            segmentCount = snapshot.segmentCount,
                            loaded = true,
                        )
                    }
                    checkBurn()
                }
        }
    }
}

private fun GeoBounds.toFocus() = MapFocus(west = west, south = south, east = east, north = north)

private const val EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"
