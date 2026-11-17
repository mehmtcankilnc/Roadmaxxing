package app.roadmaxxing.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.platform.TrackingPlatform
import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.data.SetupRepository
import app.roadmaxxing.shared.domain.DriveStatus
import app.roadmaxxing.shared.domain.ProgressRepository
import app.roadmaxxing.shared.drive.TrackingStatusHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val setupRepository: SetupRepository,
    private val driveStore: DriveStore,
    private val progressRepository: ProgressRepository,
    private val trackingStatus: TrackingStatusHolder,
    private val platform: TrackingPlatform,
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState(isDebugBuild = platform.isDebugBuild))
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val status = setupRepository.checkAndRecord()
            _state.update { it.copy(setupStatus = status) }
        }
        viewModelScope.launch {
            trackingStatus.status.collect { s -> _state.update { it.copy(tracking = s) } }
        }
        viewModelScope.launch {
            driveStore.observeDrives().collect { drives ->
                val last = drives.filter { it.status == DriveStatus.Processed && it.summary != null && it.endDecisionMs != null }
                    .maxByOrNull { it.summary!!.processedAtMs }
                val timing = last?.let { ProcessingTiming(it.summary!!.processedAtMs - it.endDecisionMs!!, it.summary!!.processingMs) }
                _state.update { it.copy(drives = drives, lastProcessing = timing) }
            }
        }
        viewModelScope.launch {
            progressRepository.observe()
                .catch { _state.update { it.copy(progress = ProgressState.Unavailable) } }
                .collect { snap -> _state.update { it.copy(progress = ProgressState.Ready(snap)) } }
        }
        refreshPermissions()
    }

    fun onIntent(intent: MainIntent) {
        when (intent) {
            MainIntent.PermissionsChanged -> {
                val before = _state.value.permissions
                refreshPermissions()
                // Re-arm the background triggers only when something actually changed.
                if (before != _state.value.permissions) platform.onPermissionsChanged()
            }
            MainIntent.ManualStart -> platform.manualStart()
            MainIntent.ManualStop -> platform.manualStop()
            is MainIntent.SelectTab -> _state.update { it.copy(selectedTab = intent.tab, detail = null) }
            is MainIntent.OpenDetail -> _state.update { it.copy(detail = intent.target) }
            MainIntent.CloseDetail -> _state.update { it.copy(detail = null) }
        }
    }

    private fun refreshPermissions() {
        _state.update { it.copy(permissions = platform.permissionStates()) }
    }
}
