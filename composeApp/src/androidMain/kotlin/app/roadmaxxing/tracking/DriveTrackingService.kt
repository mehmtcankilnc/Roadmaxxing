package app.roadmaxxing.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.notification_channel_drive
import app.roadmaxxing.resources.notification_probing_text
import app.roadmaxxing.resources.notification_probing_title
import app.roadmaxxing.resources.notification_processing_title
import app.roadmaxxing.resources.notification_recording_text
import app.roadmaxxing.resources.notification_recording_title
import app.roadmaxxing.shared.drive.ActivityType
import app.roadmaxxing.shared.drive.RecorderUpdate
import app.roadmaxxing.shared.drive.TrackingStatus
import app.roadmaxxing.shared.model.TracePoint
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.koin.android.ext.android.inject

/**
 * Location foreground service. Runs only while a drive is recorded (1 s high-accuracy fixes) or during a short
 * probe after a low-power trigger. When no drive is recorded the service is stopped and no GPS request is open.
 */
class DriveTrackingService : Service() {

    private val engine: TrackingEngine by inject()

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var looperThread: HandlerThread
    private var updatesOn = false
    private var tickJob: Job? = null
    private var probeStartedMs: Long? = null
    private var lastFix: Location? = null
    private var pointCount = 0
    private var finishing = false
    private lateinit var texts: Texts

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fixes = result.locations.toList()
            engine.scope.launch { fixes.forEach { onFix(it) } }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        looperThread = HandlerThread("rmx-location").also { it.start() }
        texts = runBlocking { Texts.load() }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, texts.channel, NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_RESUME // null intent = sticky restart after process death
        val action = intent?.action
        if (!goForeground(if (mode == MODE_PROBE) texts.probingTitle else texts.recordingTitle,
                if (mode == MODE_PROBE) texts.probingText else texts.recordingText)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        engine.scope.launch {
            when {
                action == ACTION_ENDED -> finishDrive()
                action == ACTION_MANUAL_STOP -> handle(engine.recorder.manualStop(System.currentTimeMillis()))
                else -> begin(mode)
            }
        }
        return START_STICKY
    }

    /** Runs on the tracking thread. */
    private fun begin(mode: String) {
        finishing = false
        when (mode) {
            MODE_PROBE -> if (!engine.recorder.isRecording) {
                probeStartedMs = System.currentTimeMillis()
                engine.status.set(TrackingStatus.Probing(probeStartedMs!!))
            }
            MODE_ACTIVITY -> handle(engine.recorder.onActivity(ActivityType.InVehicle, System.currentTimeMillis()))
            MODE_MANUAL -> handle(engine.recorder.manualStart(System.currentTimeMillis()))
            MODE_RESUME -> {
                if (engine.recorder.restore() == null) {
                    // Nothing to resume (e.g. sticky restart after the drive had ended): only finish pending work.
                    engine.scope.launch { finishDrive() }
                    return
                }
            }
        }
        if (engine.recorder.isRecording) publishRecording()
        startUpdates()
        startTicks()
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (updatesOn) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FIX_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, looperThread.looper)
            updatesOn = true
            Log.i(TAG, "high-accuracy updates ON (1 s)")
        } catch (e: SecurityException) {
            Log.e(TAG, "no location permission", e)
            engine.scope.launch { finishDrive() }
        }
    }

    private fun stopUpdates() {
        if (!updatesOn) return
        fused.removeLocationUpdates(callback)
        updatesOn = false
        Log.i(TAG, "high-accuracy updates OFF")
    }

    private fun startTicks() {
        if (tickJob?.isActive == true) return
        tickJob = engine.scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                handle(engine.recorder.onTick(now))
                val probe = probeStartedMs
                if (!engine.recorder.isRecording && probe != null && now - probe > PROBE_MS) {
                    Log.i(TAG, "probe ended: no drive")
                    finishDrive()
                }
            }
        }
    }

    private fun onFix(loc: Location) {
        if (finishing) return
        lastFix = loc
        val point = TracePoint(
            timeMs = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
        )
        handle(engine.recorder.onLocation(point))
        if (engine.recorder.isRecording) {
            pointCount++
            if (pointCount % 10 == 0) publishRecording()
        }
    }

    private fun handle(update: RecorderUpdate) {
        if (update.startedDriveId != null) {
            Log.i(TAG, "drive ${update.startedDriveId} started")
            probeStartedMs = null
            pointCount = 0
            publishRecording()
            goForeground(texts.recordingTitle, texts.recordingText)
            startUpdates()
            startTicks()
        }
        if (update.endedDriveId != null) {
            Log.i(TAG, "drive ${update.endedDriveId} ended")
            engine.scope.launch { finishDrive() }
        }
    }

    private fun publishRecording() {
        val id = engine.recorder.currentDriveId ?: return
        engine.status.set(TrackingStatus.Recording(id, System.currentTimeMillis(), pointCount))
    }

    /** Stops GPS, matches pending drives, re-arms the idle triggers at the last position and stops the service. */
    private suspend fun finishDrive() {
        if (finishing) return
        finishing = true
        stopUpdates()
        tickJob?.cancel()
        probeStartedMs = null
        goForeground(texts.processingTitle, null)
        engine.status.set(TrackingStatus.Processing(-1))
        engine.processPendingDrives()
        val fix = lastFix
        engine.armIdleTriggers(fix?.latitude, fix?.longitude)
        engine.status.set(TrackingStatus.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(title: String, text: String?): Boolean = try {
        startForeground(NOTIFICATION_ID, notification(title, text), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (background start without exemption) or SecurityException
        // (location FGS from background without "Allow all the time").
        Log.e(TAG, "cannot start location foreground service", e)
        false
    }

    private fun notification(title: String, text: String?): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        stopUpdates()
        tickJob?.cancel()
        looperThread.quitSafely()
        if (!engine.recorder.isRecording) engine.status.set(TrackingStatus.Idle)
        super.onDestroy()
    }

    private class Texts(
        val channel: String,
        val recordingTitle: String,
        val recordingText: String,
        val probingTitle: String,
        val probingText: String,
        val processingTitle: String,
    ) {
        companion object {
            suspend fun load() = Texts(
                channel = getString(Res.string.notification_channel_drive),
                recordingTitle = getString(Res.string.notification_recording_title),
                recordingText = getString(Res.string.notification_recording_text),
                probingTitle = getString(Res.string.notification_probing_title),
                probingText = getString(Res.string.notification_probing_text),
                processingTitle = getString(Res.string.notification_processing_title),
            )
        }
    }

    companion object {
        private const val TAG = "RmxService"
        private const val CHANNEL_ID = "drive_recording"
        private const val NOTIFICATION_ID = 1001
        private const val FIX_INTERVAL_MS = 1_000L
        private const val TICK_MS = 5_000L

        /** A probe that has not turned into a drive after this long stops GPS again. */
        private const val PROBE_MS = 120_000L

        const val EXTRA_MODE = "mode"
        const val MODE_PROBE = "probe"
        const val MODE_ACTIVITY = "activity"
        const val MODE_MANUAL = "manual"
        const val MODE_RESUME = "resume"
        const val ACTION_ENDED = "app.roadmaxxing.action.DRIVE_ENDED"
        const val ACTION_MANUAL_STOP = "app.roadmaxxing.action.MANUAL_STOP"

        /** Starts the service in [mode]. Only call from a context that may start a foreground service. */
        fun start(context: Context, mode: String): Boolean = try {
            context.startForegroundService(Intent(context, DriveTrackingService::class.java).putExtra(EXTRA_MODE, mode))
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService($mode) refused", e)
            false
        }

        fun manualStop(context: Context) {
            context.startService(Intent(context, DriveTrackingService::class.java).setAction(ACTION_MANUAL_STOP))
        }
    }
}
