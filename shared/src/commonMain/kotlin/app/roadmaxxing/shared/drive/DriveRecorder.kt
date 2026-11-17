package app.roadmaxxing.shared.drive

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.model.TracePoint

/** Outcome of one input, for the platform layer (switch GPS mode, stop the service, start processing). */
data class RecorderUpdate(
    val startedDriveId: Long? = null,
    val endedDriveId: Long? = null,
)

/**
 * Glue between [DriveDetector] and [DriveStore]: every accepted fix is written immediately, so a drive survives
 * process death. Not thread safe: call from one thread (the platform's tracking loop), off the main thread.
 */
class DriveRecorder(
    private val store: DriveStore,
    config: DriveDetectorConfig = DriveDetectorConfig(),
) {
    private val detector = DriveDetector(config)
    private var driveId: Long? = null

    val isRecording: Boolean get() = detector.isRecording
    val currentDriveId: Long? get() = driveId
    val lastActivity: ActivityType? get() = detector.lastActivityType

    /** Picks up a drive left in RECORDING state by a killed process. Returns its id or null. */
    fun restore(): Long? {
        if (detector.isRecording) return driveId
        val open = store.recordingDrive() ?: return null
        val last = store.lastPointTime(open.id) ?: open.startMs
        detector.resumeRecording(open.startMs, last)
        driveId = open.id
        return open.id
    }

    fun onLocation(point: TracePoint): RecorderUpdate = apply(detector.onLocation(point))
    fun onActivity(type: ActivityType, timeMs: Long): RecorderUpdate = apply(detector.onActivity(type, timeMs))
    fun onTick(nowMs: Long): RecorderUpdate = apply(detector.onTick(nowMs))
    fun manualStart(timeMs: Long): RecorderUpdate = apply(detector.manualStart(timeMs))
    fun manualStop(timeMs: Long): RecorderUpdate = apply(detector.manualStop(timeMs))

    private fun apply(events: List<DriveEvent>): RecorderUpdate {
        var update = RecorderUpdate()
        val batch = mutableListOf<TracePoint>()
        for (e in events) {
            when (e) {
                is DriveEvent.DriveStarted -> {
                    val id = store.createDrive(e.startTimeMs, e.reason.name)
                    driveId = id
                    batch += e.initialPoints
                    update = update.copy(startedDriveId = id)
                }
                is DriveEvent.PointAccepted -> batch += e.point
                is DriveEvent.PointDropped -> Unit
                is DriveEvent.DriveEnded -> {
                    val id = driveId
                    if (id != null) {
                        store.appendPoints(id, batch)
                        batch.clear()
                        store.markEnded(id, e.decisionTimeMs, e.reason.name, e.keepFromMs, e.keepToMs)
                        update = update.copy(endedDriveId = id)
                    }
                    driveId = null
                }
            }
        }
        driveId?.let { store.appendPoints(it, batch) }
        return update
    }
}
