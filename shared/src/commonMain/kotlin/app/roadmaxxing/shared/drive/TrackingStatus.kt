package app.roadmaxxing.shared.drive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the tracking layer is doing right now (in-process, for the UI). */
sealed interface TrackingStatus {
    /** No GPS; only the low-power triggers are armed. */
    data object Idle : TrackingStatus

    /** A low-power trigger fired; short high-accuracy check whether this is a drive. */
    data class Probing(val sinceMs: Long) : TrackingStatus

    data class Recording(val driveId: Long, val startMs: Long, val pointCount: Int) : TrackingStatus

    /** Drive ended; matching and progress update running. */
    data class Processing(val driveId: Long) : TrackingStatus
}

/** Process-wide holder written by the platform tracking service and read by the UI. */
class TrackingStatusHolder {
    private val _status = MutableStateFlow<TrackingStatus>(TrackingStatus.Idle)
    val status: StateFlow<TrackingStatus> = _status.asStateFlow()

    fun set(status: TrackingStatus) {
        _status.value = status
    }
}
