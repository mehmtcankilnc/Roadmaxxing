package app.roadmaxxing.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.data.DataActions
import app.roadmaxxing.data.ExportResult
import app.roadmaxxing.shared.settings.DistanceUnit
import app.roadmaxxing.shared.settings.QuietHours
import app.roadmaxxing.shared.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which quiet-hours boundary the time picker is editing. */
enum class QuietBoundary { Start, End }

/** Where the two-step delete flow currently is. */
enum class DeleteStep { None, Explain, Confirm }

/** One-shot message shown after an export attempt. */
enum class ExportMessage { NothingToExport, Failed }

data class SettingsUiState(
    val unit: DistanceUnit,
    val quietHours: QuietHours,
    val exporting: Boolean = false,
    val exportMessage: ExportMessage? = null,
    val deleteStep: DeleteStep = DeleteStep.None,
    val erasing: Boolean = false,
    val eraseFailed: Boolean = false,
    val editingBoundary: QuietBoundary? = null,
)

sealed interface SettingsIntent {
    data class SetUnit(val unit: DistanceUnit) : SettingsIntent
    data class SetQuietEnabled(val enabled: Boolean) : SettingsIntent
    data class EditBoundary(val boundary: QuietBoundary?) : SettingsIntent
    data class SetBoundaryTime(val boundary: QuietBoundary, val minuteOfDay: Int) : SettingsIntent
    data object Export : SettingsIntent
    data object DismissExportMessage : SettingsIntent
    data object AskDelete : SettingsIntent
    data object DeleteContinue : SettingsIntent
    data object CancelDelete : SettingsIntent
    data object ConfirmDelete : SettingsIntent
}

/** Settings screen state: units, quiet hours, export and the two-step erase. */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val data: DataActions,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(unit = settings.distanceUnit.value, quietHours = settings.quietHours.value),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { settings.distanceUnit.collect { u -> _state.update { it.copy(unit = u) } } }
        viewModelScope.launch { settings.quietHours.collect { q -> _state.update { it.copy(quietHours = q) } } }
    }

    fun onIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.SetUnit -> settings.setDistanceUnit(intent.unit)
            is SettingsIntent.SetQuietEnabled -> settings.setQuietHours(_state.value.quietHours.copy(enabled = intent.enabled))
            is SettingsIntent.EditBoundary -> _state.update { it.copy(editingBoundary = intent.boundary) }
            is SettingsIntent.SetBoundaryTime -> {
                val current = _state.value.quietHours
                settings.setQuietHours(
                    if (intent.boundary == QuietBoundary.Start) current.copy(startMinute = intent.minuteOfDay)
                    else current.copy(endMinute = intent.minuteOfDay),
                )
                _state.update { it.copy(editingBoundary = null) }
            }
            SettingsIntent.Export -> export()
            SettingsIntent.DismissExportMessage -> _state.update { it.copy(exportMessage = null) }
            SettingsIntent.AskDelete -> _state.update { it.copy(deleteStep = DeleteStep.Explain, eraseFailed = false) }
            SettingsIntent.DeleteContinue -> _state.update { it.copy(deleteStep = DeleteStep.Confirm) }
            SettingsIntent.CancelDelete -> _state.update { it.copy(deleteStep = DeleteStep.None, eraseFailed = false) }
            SettingsIntent.ConfirmDelete -> erase()
        }
    }

    private fun export() {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, exportMessage = null) }
        viewModelScope.launch {
            val result = try {
                data.exportAndShare()
            } catch (_: Exception) {
                ExportResult.Failed
            }
            _state.update {
                it.copy(
                    exporting = false,
                    exportMessage = when (result) {
                        ExportResult.Shared -> null
                        ExportResult.NothingToExport -> ExportMessage.NothingToExport
                        ExportResult.Failed -> ExportMessage.Failed
                    },
                )
            }
        }
    }

    private fun erase() {
        if (_state.value.erasing) return
        _state.update { it.copy(erasing = true, eraseFailed = false) }
        viewModelScope.launch {
            try {
                data.eraseEverything()
                // The app resets to onboarding on success and this overlay leaves with it.
                _state.update { it.copy(erasing = false, deleteStep = DeleteStep.None) }
            } catch (_: Exception) {
                // Keep the confirm dialog open with an error so the user can retry.
                _state.update { it.copy(erasing = false, eraseFailed = true) }
            }
        }
    }
}
