package app.roadmaxxing.tracking

import android.content.Context
import android.content.Intent
import android.util.Log
import app.roadmaxxing.notify.DriveResultNotifier
import app.roadmaxxing.shared.domain.DriveProcessor
import app.roadmaxxing.shared.drive.ActivityType
import app.roadmaxxing.shared.drive.DriveRecorder
import app.roadmaxxing.shared.drive.TrackingStatus
import app.roadmaxxing.shared.drive.TrackingStatusHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Process-wide owner of the Android tracking pieces. Every call into [DriveRecorder] goes through
 * [trackingDispatcher] (one thread), so the service, the receivers and the UI never race on it.
 */
class TrackingEngine(
    context: Context,
    val recorder: DriveRecorder,
    val processor: DriveProcessor,
    val status: TrackingStatusHolder,
    private val notifier: DriveResultNotifier,
) {
    private val app = context.applicationContext
    val triggers = IdleTriggers(app)
    val trackingDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "rmx-tracking") }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + trackingDispatcher)

    /** Last activity transition seen, with its time (for skipping probes while the user walks). */
    @Volatile var lastActivity: Pair<ActivityType, Long>? = null
        private set

    /**
     * Called on every process start (Application.onCreate): resumes a drive the killed process left open,
     * processes drives that ended but were not matched yet, and re-arms the idle triggers.
     */
    fun onProcessStart() {
        scope.launch {
            val open = recorder.restore()
            if (open != null) {
                Log.i(TAG, "resuming drive $open after process restart")
                DriveTrackingService.start(app, DriveTrackingService.MODE_RESUME)
            } else {
                processPendingDrives()
                armIdleTriggers()
            }
        }
    }

    suspend fun armIdleTriggers(lat: Double? = null, lon: Double? = null) {
        val ar = triggers.armActivityTransitions()
        val fence = triggers.armGeofence(lat, lon)
        Log.i(TAG, "idle triggers armed: activity=$ar geofence=$fence")
    }

    suspend fun processPendingDrives() {
        val processed = runCatching { processor.processPending() }
            .onFailure { Log.e(TAG, "processing failed; drives stay PENDING and are retried", it) }
            .getOrDefault(emptyList())
        if (processed.isNotEmpty()) {
            Log.i(TAG, "processed drives $processed")
            notifier.onDrivesProcessed(processed)
        }
    }

    /** Activity transition from Play services. Starts recording on IN_VEHICLE. */
    suspend fun onActivity(type: ActivityType, timeMs: Long) = withContext(trackingDispatcher) {
        lastActivity = type to timeMs
        if (recorder.isRecording) {
            // The running service sees the activity through the recorder on its next fix/tick.
            val update = recorder.onActivity(type, timeMs)
            if (update.endedDriveId != null) app.startService(Intent(app, DriveTrackingService::class.java).setAction(DriveTrackingService.ACTION_ENDED))
        } else if (type == ActivityType.InVehicle) {
            DriveTrackingService.start(app, DriveTrackingService.MODE_ACTIVITY)
        } else {
            recorder.onActivity(type, timeMs)
        }
    }

    /** The stationary geofence was left: probe with GPS unless the user is known to be on foot. */
    suspend fun onGeofenceExit(lat: Double?, lon: Double?, timeMs: Long) = withContext(trackingDispatcher) {
        if (recorder.isRecording || status.status.value !is TrackingStatus.Idle) return@withContext
        val act = lastActivity
        val onFootRecently = act != null && act.first.isOutOfVehicle && timeMs - act.second < ON_FOOT_SKIP_MS
        if (onFootRecently) {
            Log.i(TAG, "geofence exit while on foot: re-arming without probe")
            armIdleTriggers(lat, lon)
        } else {
            DriveTrackingService.start(app, DriveTrackingService.MODE_PROBE)
        }
    }

    companion object {
        private const val TAG = "RmxTracking"
        private const val ON_FOOT_SKIP_MS = 10 * 60_000L
    }
}
