package app.roadmaxxing.tracking

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine. First CI run may need
// small fixes to CoreLocation / CoreMotion / UIKit interop signatures (see the report's risk list).

import app.roadmaxxing.platform.AppPermission
import app.roadmaxxing.platform.PermissionState
import app.roadmaxxing.shared.domain.DriveProcessor
import app.roadmaxxing.shared.domain.currentTimeMs
import app.roadmaxxing.shared.drive.ActivityType
import app.roadmaxxing.shared.drive.DriveRecorder
import app.roadmaxxing.shared.drive.RecorderUpdate
import app.roadmaxxing.shared.drive.TrackingStatus
import app.roadmaxxing.shared.drive.TrackingStatusHolder
import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.model.TracePoint
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.CoreLocation.CLActivityTypeAutomotiveNavigation
import platform.CoreLocation.CLAccuracyAuthorizationReducedAccuracy
import platform.CoreLocation.CLCircularRegion
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMAuthorizationStatusNotDetermined
import platform.CoreMotion.CMMotionActivity
import platform.CoreMotion.CMMotionActivityConfidenceLow
import platform.CoreMotion.CMMotionActivityManager
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * iOS counterpart of Android's TrackingEngine + DriveTrackingService + IdleTriggers.
 *
 * All detection, validation, matching and progress rules live in shared code ([DriveRecorder], [DriveProcessor]);
 * this class only maps CoreLocation / CoreMotion to them and switches GPS on and off:
 *
 * - Idle (no drive): NO continuous GPS. Triggers are the significant-location-change service and one 200 m
 *   "stationary" region (exit only), plus CoreMotion activity updates while the process is alive.
 * - Probe / drive: [driveManager] runs high-accuracy 1 Hz updates with background updates allowed. When the drive
 *   ends the updates are stopped and the idle triggers are re-armed at the last fix.
 *
 * Threading: CLLocationManager and CMMotionActivityManager callbacks arrive on the main thread (the managers are
 * created on it: this class MUST be constructed on the main thread, which [startTrackingOnLaunch] guarantees).
 * Every call into [DriveRecorder] and every field marked "tracking scope" runs on [scope], a serial dispatcher,
 * so recorder access is single threaded and off the main thread. Manager calls hop back with [onMain].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalCoroutinesApi::class)
class IosTrackingEngine(
    private val recorder: DriveRecorder,
    private val processor: DriveProcessor,
    private val status: TrackingStatusHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    // Strong references: CLLocationManager.delegate is unretained, so the delegate must be owned here.
    private val delegate = IosLocationDelegate(this)
    internal val idleManager = CLLocationManager().also { it.delegate = delegate }
    internal val driveManager = CLLocationManager().also { it.delegate = delegate }
    private val motion = CMMotionActivityManager()

    // ---- main thread only ----
    private var started = false
    private var motionRunning = false
    private var updatesOn = false
    private var bgTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private val authCallbacks = mutableListOf<() -> Unit>()
    private val resumeCallbacks = mutableListOf<() -> Unit>()
    private var activeObserver: Any? = null

    // ---- tracking scope only ----
    private var lastActivity: Pair<ActivityType, Long>? = null
    private var sessionActive = false
    private var finishing = false
    private var probeStartedMs: Long? = null
    private var pointCount = 0
    private var lastFixLat: Double? = null
    private var lastFixLon: Double? = null
    private var idleCenter: Pair<Double, Double>? = null
    private var awaitingCenter = false
    private var tickJob: Job? = null

    // ------------------------------------------------------------------ lifecycle

    /**
     * Called on every process start (also when iOS relaunches the app in the background): resumes a drive the
     * killed process left open, processes unmatched drives and arms the idle triggers. Main thread.
     */
    fun start() {
        if (started) return
        started = true
        activeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidBecomeActiveNotification,
            null,
            NSOperationQueue.mainQueue,
        ) { _ ->
            val pending = resumeCallbacks.toList()
            resumeCallbacks.clear()
            pending.forEach { it() }
        }
        startMotionUpdates()
        scope.launch {
            val open = recorder.restore()
            if (open != null) {
                println("$TAG resuming drive $open after process restart")
                beginSession(Mode.Resume)
            } else {
                processPendingDrives()
                // keepExisting: a region exit that relaunched us is delivered right now; do not remove its region.
                armIdle(null, null, keepExistingRegion = true)
            }
        }
    }

    fun onPermissionsChanged() {
        onMain { startMotionUpdates() }
        scope.launch { if (!sessionActive) armIdle(null, null, keepExistingRegion = false) }
    }

    fun manualStart() {
        scope.launch { beginSession(Mode.Manual) }
    }

    fun manualStop() {
        scope.launch { handle(recorder.manualStop(currentTimeMs())) }
    }

    // ------------------------------------------------------------------ permissions (main thread)

    fun permissionStates(): Map<AppPermission, PermissionState> {
        val auth = idleManager.authorizationStatus
        val whenInUse = auth == kCLAuthorizationStatusAuthorizedWhenInUse || auth == kCLAuthorizationStatusAuthorizedAlways
        val precise = idleManager.accuracyAuthorization != CLAccuracyAuthorizationReducedAccuracy
        val motionState = when {
            !CMMotionActivityManager.isActivityAvailable() -> PermissionState.NotNeeded // simulator: speed path only
            CMMotionActivityManager.authorizationStatus() == CMAuthorizationStatusAuthorized -> PermissionState.Granted
            else -> PermissionState.Denied
        }
        return mapOf(
            AppPermission.Location to if (whenInUse && precise) PermissionState.Granted else PermissionState.Denied,
            AppPermission.BackgroundLocation to
                if (auth == kCLAuthorizationStatusAuthorizedAlways) PermissionState.Granted else PermissionState.Denied,
            AppPermission.ActivityRecognition to motionState,
            AppPermission.Notifications to PermissionState.NotNeeded, // no notifications on iOS in Phase 0
        )
    }

    /** Asks for one permission; [onResult] runs when the system dialog closed or the user came back from Settings. */
    fun request(permission: AppPermission, onResult: () -> Unit) {
        val auth = idleManager.authorizationStatus
        when (permission) {
            AppPermission.Location ->
                if (auth == kCLAuthorizationStatusNotDetermined) {
                    authCallbacks += onResult
                    idleManager.requestWhenInUseAuthorization()
                } else {
                    openSettings(onResult)
                }
            AppPermission.BackgroundLocation -> when {
                auth == kCLAuthorizationStatusNotDetermined -> {
                    // iOS asks in two steps: "While Using" first; "Always" can only be requested afterwards.
                    authCallbacks += onResult
                    idleManager.requestWhenInUseAuthorization()
                }
                auth == kCLAuthorizationStatusAuthorizedWhenInUse && !askedAlwaysBefore() -> {
                    // iOS shows the upgrade prompt only once per install; later calls do nothing, so go to Settings.
                    markAskedAlways()
                    authCallbacks += onResult
                    idleManager.requestAlwaysAuthorization()
                }
                else -> openSettings(onResult)
            }
            AppPermission.ActivityRecognition ->
                if (CMMotionActivityManager.authorizationStatus() == CMAuthorizationStatusNotDetermined) {
                    // There is no request API: the first query shows the Motion & Fitness prompt.
                    val now = NSDate()
                    motion.queryActivityStartingFromDate(now, now, NSOperationQueue.mainQueue) { _, _ -> onResult() }
                } else {
                    openSettings(onResult)
                }
            AppPermission.Notifications -> onResult()
        }
    }

    internal fun onAuthorizationChanged() {
        val pending = authCallbacks.toList()
        authCallbacks.clear()
        pending.forEach { it() }
        onPermissionsChangedFromSystem()
    }

    private fun onPermissionsChangedFromSystem() {
        scope.launch { if (!sessionActive) armIdle(null, null, keepExistingRegion = true) }
    }

    private fun openSettings(onResult: () -> Unit) {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
        if (url == null) {
            onResult()
            return
        }
        resumeCallbacks += onResult
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
    }

    private fun askedAlwaysBefore() = NSUserDefaults.standardUserDefaults.boolForKey(KEY_ASKED_ALWAYS)
    private fun markAskedAlways() = NSUserDefaults.standardUserDefaults.setBool(true, forKey = KEY_ASKED_ALWAYS)

    // ------------------------------------------------------------------ inputs from the delegate / CoreMotion (main thread)

    internal fun onLocations(manager: CLLocationManager, locations: List<CLLocation>) {
        val points = locations.mapNotNull { it.toTracePoint() }
        if (points.isEmpty()) return
        if (manager === driveManager) {
            scope.launch { points.forEach { onFix(it) } }
        } else {
            val last = points.last()
            scope.launch { onIdleLocation(last.lat, last.lon, last.timeMs) }
        }
    }

    internal fun onRegionExit(manager: CLLocationManager) {
        var lat: Double? = null
        var lon: Double? = null
        manager.location?.coordinate?.useContents { lat = latitude; lon = longitude }
        val now = currentTimeMs()
        scope.launch { wake(lat, lon, now, reason = "region exit") }
    }

    internal fun onLocationError(message: String) {
        println("$TAG location error: $message")
    }

    private fun startMotionUpdates() {
        if (motionRunning) return
        if (!CMMotionActivityManager.isActivityAvailable()) return
        if (CMMotionActivityManager.authorizationStatus() != CMAuthorizationStatusAuthorized) return
        motionRunning = true
        motion.startActivityUpdatesToQueue(NSOperationQueue.mainQueue) { activity ->
            val type = activity?.toActivityType() ?: return@startActivityUpdatesToQueue
            val now = currentTimeMs()
            scope.launch { onActivity(type, now) }
        }
    }

    // ------------------------------------------------------------------ logic (tracking scope)

    private fun onFix(point: TracePoint) {
        if (!sessionActive || finishing) return
        lastFixLat = point.lat
        lastFixLon = point.lon
        handle(recorder.onLocation(point))
        if (recorder.isRecording) {
            pointCount++
            if (pointCount % 10 == 0) publishRecording()
        }
    }

    private fun onActivity(type: ActivityType, timeMs: Long) {
        lastActivity = type to timeMs
        if (recorder.isRecording) {
            handle(recorder.onActivity(type, timeMs))
        } else if (type == ActivityType.InVehicle) {
            beginSession(Mode.Activity)
        } else {
            recorder.onActivity(type, timeMs)
        }
    }

    /** A significant-change fix while idle. Fixes still inside the stationary fence are ignored. */
    private suspend fun onIdleLocation(lat: Double, lon: Double, timeMs: Long) {
        if (sessionActive || recorder.isRecording) return
        val center = idleCenter
        if (center == null || awaitingCenter) {
            awaitingCenter = false
            armIdle(lat, lon, keepExistingRegion = false)
            return
        }
        if (Geo.distanceM(center.first, center.second, lat, lon) < IDLE_IGNORE_RADIUS_M) return
        wake(lat, lon, timeMs, reason = "significant change")
    }

    /** A low-power trigger fired: decide between an activity start, no probe (on foot) and a short GPS probe. */
    private suspend fun wake(lat: Double?, lon: Double?, timeMs: Long, reason: String) {
        if (sessionActive || recorder.isRecording) return
        queryRecentActivity(timeMs)?.let { lastActivity = it }
        val act = lastActivity
        val onFootRecently = act != null && act.first.isOutOfVehicle && timeMs - act.second < ON_FOOT_SKIP_MS
        println("$TAG wake ($reason): lastActivity=${act?.first} onFootRecently=$onFootRecently")
        when {
            act?.first == ActivityType.InVehicle && timeMs - act.second < RECENT_VEHICLE_MS -> beginSession(Mode.Activity)
            onFootRecently -> armIdle(lat, lon, keepExistingRegion = false)
            else -> beginSession(Mode.Probe)
        }
    }

    private fun beginSession(mode: Mode) {
        val now = currentTimeMs()
        if (sessionActive) {
            // Already probing or recording: only feed the activity.
            if (mode == Mode.Activity) handle(recorder.onActivity(ActivityType.InVehicle, now))
            return
        }
        sessionActive = true
        finishing = false
        when (mode) {
            Mode.Probe -> {
                probeStartedMs = now
                status.set(TrackingStatus.Probing(now))
            }
            Mode.Activity -> handle(recorder.onActivity(ActivityType.InVehicle, now))
            Mode.Manual -> handle(recorder.manualStart(now))
            Mode.Resume -> if (recorder.restore() == null) {
                scope.launch { finishDrive() }
                return
            }
        }
        if (recorder.isRecording) publishRecording()
        startUpdates()
        startTicks()
    }

    private fun handle(update: RecorderUpdate) {
        if (update.startedDriveId != null) {
            println("$TAG drive ${update.startedDriveId} started")
            probeStartedMs = null
            pointCount = 0
            publishRecording()
        }
        if (update.endedDriveId != null) {
            println("$TAG drive ${update.endedDriveId} ended")
            scope.launch { finishDrive() }
        }
    }

    private fun publishRecording() {
        val id = recorder.currentDriveId ?: return
        status.set(TrackingStatus.Recording(id, currentTimeMs(), pointCount))
    }

    private fun startTicks() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val now = currentTimeMs()
                handle(recorder.onTick(now))
                val probe = probeStartedMs
                if (!recorder.isRecording && probe != null && now - probe > PROBE_MS) {
                    println("$TAG probe ended: no drive")
                    finishDrive()
                }
            }
        }
    }

    /** Stops GPS, matches pending drives, re-arms the idle triggers at the last position. */
    private suspend fun finishDrive() {
        if (finishing) return
        finishing = true
        stopUpdates()
        tickJob?.cancel()
        tickJob = null
        probeStartedMs = null
        beginBackgroundTask()
        status.set(TrackingStatus.Processing(-1))
        processPendingDrives()
        armIdle(lastFixLat, lastFixLon, keepExistingRegion = false)
        sessionActive = false
        status.set(TrackingStatus.Idle)
        endBackgroundTask()
    }

    private suspend fun processPendingDrives() {
        val processed = runCatching { processor.processPending() }
            .onFailure { println("$TAG processing failed; drives stay PENDING and are retried: $it") }
            .getOrDefault(emptyList())
        if (processed.isNotEmpty()) println("$TAG processed drives $processed")
    }

    // ------------------------------------------------------------------ GPS and idle triggers

    private fun startUpdates() {
        if (updatesOn) return
        updatesOn = true
        onMain {
            driveManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
            driveManager.distanceFilter = kCLDistanceFilterNone
            driveManager.activityType = CLActivityTypeAutomotiveNavigation
            driveManager.pausesLocationUpdatesAutomatically = false
            // Requires UIBackgroundModes "location" in Info.plist (present); shows the blue status-bar pill.
            driveManager.allowsBackgroundLocationUpdates = true
            driveManager.showsBackgroundLocationIndicator = true
            driveManager.startUpdatingLocation()
            println("$TAG high-accuracy updates ON")
        }
    }

    private fun stopUpdates() {
        if (!updatesOn) return
        updatesOn = false
        onMain {
            driveManager.stopUpdatingLocation()
            driveManager.showsBackgroundLocationIndicator = false
            driveManager.allowsBackgroundLocationUpdates = false
            println("$TAG high-accuracy updates OFF")
        }
    }

    /**
     * Arms the idle triggers: significant-change service plus a [REGION_RADIUS_M] exit region around ([lat], [lon]).
     * With no position the current one is requested once. Triggers need "Always" authorization.
     */
    private fun armIdle(lat: Double?, lon: Double?, keepExistingRegion: Boolean) {
        if (lat != null && lon != null) {
            idleCenter = lat to lon
            awaitingCenter = false
        }
        onMain {
            if (idleManager.authorizationStatus != kCLAuthorizationStatusAuthorizedAlways) {
                println("$TAG idle triggers not armed: no Always authorization")
                return@onMain
            }
            idleManager.startMonitoringSignificantLocationChanges()
            val existing = idleManager.monitoredRegions.filterIsInstance<CLCircularRegion>()
            if (keepExistingRegion && existing.isNotEmpty()) {
                var cLat = 0.0
                var cLon = 0.0
                existing.first().center.useContents { cLat = latitude; cLon = longitude }
                scope.launch { if (idleCenter == null) idleCenter = cLat to cLon }
                return@onMain
            }
            existing.forEach { idleManager.stopMonitoringForRegion(it) }
            if (lat != null && lon != null) {
                val region = CLCircularRegion(
                    center = CLLocationCoordinate2DMake(lat, lon),
                    radius = REGION_RADIUS_M,
                    identifier = REGION_ID,
                )
                region.notifyOnEntry = false
                region.notifyOnExit = true
                idleManager.startMonitoringForRegion(region)
                println("$TAG idle region armed r=$REGION_RADIUS_M")
            } else {
                scope.launch { awaitingCenter = true }
                idleManager.requestLocation() // one-shot, answered via didUpdateLocations on idleManager
            }
        }
    }

    private suspend fun queryRecentActivity(nowMs: Long): Pair<ActivityType, Long>? = suspendCoroutine { cont ->
        if (!CMMotionActivityManager.isActivityAvailable() ||
            CMMotionActivityManager.authorizationStatus() != CMAuthorizationStatusAuthorized
        ) {
            cont.resume(null)
            return@suspendCoroutine
        }
        val from = NSDate.dateWithTimeIntervalSince1970((nowMs - RECENT_QUERY_MS) / 1000.0)
        val to = NSDate.dateWithTimeIntervalSince1970(nowMs / 1000.0)
        onMain {
            motion.queryActivityStartingFromDate(from, to, NSOperationQueue.mainQueue) { list, _ ->
                val newest = list?.filterIsInstance<CMMotionActivity>()
                    ?.mapNotNull { a -> a.toActivityType()?.let { it to (a.startDate.timeIntervalSince1970 * 1000.0).toLong() } }
                    ?.lastOrNull()
                cont.resume(newest)
            }
        }
    }

    // ------------------------------------------------------------------ background task (keeps processing alive after the drive)

    private fun beginBackgroundTask() = onMain {
        if (bgTask == UIBackgroundTaskInvalid) {
            bgTask = UIApplication.sharedApplication.beginBackgroundTaskWithName("rmx-process") { endBackgroundTaskNow() }
        }
    }

    private fun endBackgroundTask() = onMain { endBackgroundTaskNow() }

    private fun endBackgroundTaskNow() {
        if (bgTask != UIBackgroundTaskInvalid) {
            UIApplication.sharedApplication.endBackgroundTask(bgTask)
            bgTask = UIBackgroundTaskInvalid
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun onMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) { block() }
    }

    private fun CLLocation.toTracePoint(): TracePoint? {
        if (horizontalAccuracy < 0) return null // invalid fix
        var lat = 0.0
        var lon = 0.0
        coordinate.useContents { lat = latitude; lon = longitude }
        return TracePoint(
            timeMs = (timestamp.timeIntervalSince1970 * 1000.0).toLong(),
            lat = lat,
            lon = lon,
            speedMps = if (speed >= 0) speed else null,
            bearingDeg = if (course >= 0) course else null,
            accuracyM = horizontalAccuracy,
        )
    }

    private fun CMMotionActivity.toActivityType(): ActivityType? = when {
        unknown || confidence == CMMotionActivityConfidenceLow -> null
        automotive -> ActivityType.InVehicle
        cycling -> ActivityType.OnBicycle
        walking || running -> ActivityType.OnFoot
        stationary -> ActivityType.Still
        else -> ActivityType.Unknown
    }

    private enum class Mode { Probe, Activity, Manual, Resume }

    companion object {
        private const val TAG = "[RmxTracking]"
        private const val REGION_ID = "stationary"
        private const val KEY_ASKED_ALWAYS = "rmx.askedAlwaysAuthorization"

        /** Same as Android's stationary geofence (iOS ignores region radii below ~100 m anyway). */
        const val REGION_RADIUS_M = 200.0

        /** Significant-change fixes closer than this to the idle centre are not a departure. */
        private const val IDLE_IGNORE_RADIUS_M = 150.0
        private const val TICK_MS = 5_000L
        private const val PROBE_MS = 120_000L
        private const val ON_FOOT_SKIP_MS = 10 * 60_000L
        private const val RECENT_VEHICLE_MS = 5 * 60_000L
        private const val RECENT_QUERY_MS = 5 * 60_000L
    }
}
