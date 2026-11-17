package app.roadmaxxing.ui.main

import app.roadmaxxing.platform.AppPermission
import app.roadmaxxing.platform.PermissionState
import app.roadmaxxing.shared.domain.DriveRecord
import app.roadmaxxing.shared.domain.ProgressSnapshot
import app.roadmaxxing.shared.domain.SetupStatus
import app.roadmaxxing.shared.drive.TrackingStatus

/** Region progress loading state. */
sealed interface ProgressState {
    data object Loading : ProgressState
    data object Unavailable : ProgressState
    data class Ready(val snapshot: ProgressSnapshot) : ProgressState
}

/** Timing of the latest processed drive (Phase 0 criterion: progress updated <= 60 s after the drive ended). */
data class ProcessingTiming(
    /** From the end decision to the stored result. */
    val afterEndMs: Long,
    /** Matching and storing alone. */
    val processingMs: Long,
)

/** Immutable screen state (MVI model). */
data class MainUiState(
    val setupStatus: SetupStatus? = null,
    val tracking: TrackingStatus = TrackingStatus.Idle,
    val permissions: Map<AppPermission, PermissionState> = emptyMap(),
    val drives: List<DriveRecord> = emptyList(),
    val progress: ProgressState = ProgressState.Loading,
    val lastProcessing: ProcessingTiming? = null,
    val isDebugBuild: Boolean = false,
)

/** Intents handled by [MainViewModel]. */
sealed interface MainIntent {
    /** Re-read permission states (after a dialog or returning from settings). */
    data object PermissionsChanged : MainIntent
    data object ManualStart : MainIntent
    data object ManualStop : MainIntent
}
