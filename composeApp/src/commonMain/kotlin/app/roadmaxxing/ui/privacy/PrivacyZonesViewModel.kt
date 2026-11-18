package app.roadmaxxing.ui.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.shared.map.DrivenRoadsProvider
import app.roadmaxxing.shared.privacy.PrivacyZone
import app.roadmaxxing.shared.privacy.PrivacyZoneRepository
import app.roadmaxxing.shared.privacy.ZoneKind
import app.roadmaxxing.shared.privacy.ZoneSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Zone being created or edited. [id] is null for a new zone; [centerLat]/[centerLon] only seed the map camera. */
data class ZoneEditor(
    val id: Long?,
    val kind: ZoneKind,
    val name: String,
    val radiusM: Int,
    val centerLat: Double,
    val centerLon: Double,
)

data class PrivacyZonesUiState(
    val zones: List<PrivacyZone> = emptyList(),
    val suggestion: ZoneSuggestion? = null,
    val editor: ZoneEditor? = null,
    /** Zone waiting for the delete confirmation. */
    val pendingDelete: PrivacyZone? = null,
)

sealed interface PrivacyZonesIntent {
    data object AddZone : PrivacyZonesIntent
    data class EditZone(val zone: PrivacyZone) : PrivacyZonesIntent
    data object AcceptSuggestion : PrivacyZonesIntent
    data object DismissSuggestion : PrivacyZonesIntent
    data class SetKind(val kind: ZoneKind) : PrivacyZonesIntent
    data class SetName(val name: String) : PrivacyZonesIntent
    data class SetRadius(val radiusM: Int) : PrivacyZonesIntent
    /** Saves the editor with the map's current centre. */
    data class Save(val lat: Double, val lon: Double) : PrivacyZonesIntent
    data object CancelEditor : PrivacyZonesIntent
    data class AskDelete(val zone: PrivacyZone) : PrivacyZonesIntent
    data object ConfirmDelete : PrivacyZonesIntent
    data object CancelDelete : PrivacyZonesIntent
}

/** Privacy zones list and editor state. Repository calls run off the main thread. */
class PrivacyZonesViewModel(
    private val repository: PrivacyZoneRepository,
    private val roads: DrivenRoadsProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(PrivacyZonesUiState(zones = repository.zones.value))
    val state: StateFlow<PrivacyZonesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { repository.zones.collect { zones -> _state.update { it.copy(zones = zones) } } }
        viewModelScope.launch {
            val suggestion = try {
                withContext(Dispatchers.Default) { repository.suggestion() }
            } catch (_: Exception) {
                null
            }
            _state.update { it.copy(suggestion = suggestion) }
        }
    }

    fun onIntent(intent: PrivacyZonesIntent) {
        when (intent) {
            PrivacyZonesIntent.AddZone -> viewModelScope.launch {
                val (lat, lon) = fallbackCenter()
                _state.update { it.copy(editor = newEditor(ZoneKind.Home, lat, lon)) }
            }
            is PrivacyZonesIntent.EditZone -> _state.update {
                val z = intent.zone
                it.copy(editor = ZoneEditor(z.id, z.kind, z.name.orEmpty(), z.radiusM, z.lat, z.lon))
            }
            PrivacyZonesIntent.AcceptSuggestion -> _state.value.suggestion?.let { s ->
                _state.update { it.copy(editor = newEditor(ZoneKind.Home, s.lat, s.lon)) }
            }
            PrivacyZonesIntent.DismissSuggestion -> {
                _state.update { it.copy(suggestion = null) }
                viewModelScope.launch(Dispatchers.Default) { repository.dismissSuggestion() }
            }
            is PrivacyZonesIntent.SetKind -> _state.update { s -> s.copy(editor = s.editor?.copy(kind = intent.kind)) }
            is PrivacyZonesIntent.SetName -> _state.update { s -> s.copy(editor = s.editor?.copy(name = intent.name.take(MAX_NAME))) }
            is PrivacyZonesIntent.SetRadius -> _state.update { s ->
                s.copy(editor = s.editor?.copy(radiusM = intent.radiusM.coerceIn(MIN_RADIUS, MAX_RADIUS)))
            }
            is PrivacyZonesIntent.Save -> save(intent.lat, intent.lon)
            PrivacyZonesIntent.CancelEditor -> _state.update { it.copy(editor = null) }
            is PrivacyZonesIntent.AskDelete -> _state.update { it.copy(pendingDelete = intent.zone) }
            PrivacyZonesIntent.CancelDelete -> _state.update { it.copy(pendingDelete = null) }
            PrivacyZonesIntent.ConfirmDelete -> {
                val zone = _state.value.pendingDelete ?: return
                _state.update { it.copy(pendingDelete = null, editor = it.editor?.takeIf { e -> e.id != zone.id }) }
                viewModelScope.launch(Dispatchers.Default) { repository.delete(zone.id) }
            }
        }
    }

    private fun save(lat: Double, lon: Double) {
        val editor = _state.value.editor ?: return
        val name = if (editor.kind == ZoneKind.Custom) editor.name.trim().ifEmpty { null } else null
        val wasSuggestion = editor.id == null && _state.value.suggestion != null
        _state.update { it.copy(editor = null, suggestion = if (wasSuggestion) null else it.suggestion) }
        viewModelScope.launch(Dispatchers.Default) {
            if (editor.id == null) {
                repository.add(editor.kind, name, lat, lon, editor.radiusM)
            } else {
                repository.update(PrivacyZone(editor.id, editor.kind, name, lat, lon, editor.radiusM))
            }
        }
    }

    private fun newEditor(kind: ZoneKind, lat: Double, lon: Double) =
        ZoneEditor(null, kind, "", PrivacyZoneRepository.DEFAULT_RADIUS_M, lat, lon)

    /** Where the map starts for a brand-new zone: centre of the area driven so far, else the pilot city. */
    private suspend fun fallbackCenter(): Pair<Double, Double> {
        val b = try {
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(2_000) { roads.observe().first().drivenBounds } ?: roads.provinceBounds()
            }
        } catch (_: Exception) {
            null
        }
        return if (b != null) (b.south + b.north) / 2 to (b.west + b.east) / 2 else ADANA_LAT to ADANA_LON
    }

    companion object {
        const val MIN_RADIUS = PrivacyZoneRepository.MIN_RADIUS_M
        const val MAX_RADIUS = PrivacyZoneRepository.MAX_RADIUS_M
        const val RADIUS_STEP = 50
        private const val MAX_NAME = 40
        private const val ADANA_LAT = 37.0
        private const val ADANA_LON = 35.3213
    }
}
