package app.roadmaxxing.platform

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted
import platform.CoreMotion.CMAuthorizationStatusAuthorized
import platform.CoreMotion.CMAuthorizationStatusNotDetermined
import platform.CoreMotion.CMMotionActivityManager
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS permission reading and requesting for the permissions card and onboarding. Everything runs on the main
 * thread (Compose UI callbacks and CoreLocation delegate callbacks both arrive there).
 *
 * It owns a separate [CLLocationManager] only to ask for authorization and to hear about changes; the
 * authorization status is app-wide, so the tracking engine's managers see the same value.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosPermissions {

    private const val KEY_ASKED_ALWAYS = "rmx.askedAlwaysAuthorization"

    private val delegate = AuthDelegate { onAuthorizationChanged() }
    private val manager = CLLocationManager().also { it.delegate = delegate }
    private val motion = CMMotionActivityManager()

    /** Callbacks waiting for a system dialog to close. */
    private val authCallbacks = mutableListOf<() -> Unit>()
    private val resumeCallbacks = mutableListOf<() -> Unit>()
    private var resumeObserver: Any? = null

    /** Notification status is only available asynchronously: cached here, refreshed on every read. */
    private var notificationStatus: Long? = null

    fun states(): Map<AppPermission, PermissionState> {
        refreshNotificationStatus()
        val auth = manager.authorizationStatus
        val denied = auth == kCLAuthorizationStatusDenied || auth == kCLAuthorizationStatusRestricted
        val whenInUse = auth == kCLAuthorizationStatusAuthorizedWhenInUse || auth == kCLAuthorizationStatusAuthorizedAlways
        val reduced = manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationReducedAccuracy

        val location = when {
            whenInUse && reduced -> PermissionState.Approximate
            whenInUse -> PermissionState.Granted
            denied -> PermissionState.Blocked
            else -> PermissionState.Denied
        }
        val background = when {
            auth == kCLAuthorizationStatusAuthorizedAlways -> PermissionState.Granted
            denied -> PermissionState.Blocked
            // The "Always" upgrade prompt is shown once per install; afterwards only Settings can change it.
            auth == kCLAuthorizationStatusAuthorizedWhenInUse && askedAlwaysBefore() -> PermissionState.Blocked
            else -> PermissionState.Denied
        }
        val motionState = when {
            !CMMotionActivityManager.isActivityAvailable() -> PermissionState.NotNeeded // simulator: speed path only
            CMMotionActivityManager.authorizationStatus() == CMAuthorizationStatusAuthorized -> PermissionState.Granted
            CMMotionActivityManager.authorizationStatus() == CMAuthorizationStatusNotDetermined -> PermissionState.Denied
            else -> PermissionState.Blocked
        }
        val notifications = when (notificationStatus) {
            null, UNAuthorizationStatusNotDetermined -> PermissionState.Denied
            UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> PermissionState.Granted
            UNAuthorizationStatusDenied -> PermissionState.Blocked
            else -> PermissionState.Blocked
        }
        return mapOf(
            AppPermission.Location to location,
            AppPermission.BackgroundLocation to background,
            AppPermission.ActivityRecognition to motionState,
            AppPermission.Notifications to notifications,
        )
    }

    /** Asks for one permission; [onResult] runs when the system dialog closed or the user came back from Settings. */
    fun request(permission: AppPermission, onResult: () -> Unit) {
        val auth = manager.authorizationStatus
        when (permission) {
            AppPermission.Location ->
                if (auth == kCLAuthorizationStatusNotDetermined) {
                    authCallbacks += onResult
                    manager.requestWhenInUseAuthorization()
                } else {
                    openSettings(onResult)
                }
            AppPermission.BackgroundLocation -> when {
                auth == kCLAuthorizationStatusNotDetermined -> {
                    // iOS asks in two steps: "While Using" first; "Always" can only be requested afterwards.
                    authCallbacks += onResult
                    manager.requestWhenInUseAuthorization()
                }
                auth == kCLAuthorizationStatusAuthorizedWhenInUse && !askedAlwaysBefore() -> {
                    markAskedAlways()
                    authCallbacks += onResult
                    manager.requestAlwaysAuthorization()
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
            AppPermission.Notifications -> requestNotifications(onResult)
        }
    }

    /** Opens this app's page in the system Settings; [onReturn] (optional) runs when the app becomes active again. */
    fun openSettings(onReturn: (() -> Unit)? = null) {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString)
        if (url == null) {
            onReturn?.invoke()
            return
        }
        if (onReturn != null) {
            resumeCallbacks += onReturn
            ensureResumeObserver()
        }
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
    }

    private fun requestNotifications(onResult: () -> Unit) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            dispatch_async(dispatch_get_main_queue()) {
                notificationStatus = status
                if (status == UNAuthorizationStatusNotDetermined) {
                    center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ ->
                        dispatch_async(dispatch_get_main_queue()) {
                            notificationStatus = null
                            onResult()
                        }
                    }
                } else {
                    openSettings(onResult)
                }
            }
        }
    }

    private fun refreshNotificationStatus() {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            dispatch_async(dispatch_get_main_queue()) {
                if (status != notificationStatus) {
                    notificationStatus = status
                    PermissionRefreshObserver.notifyChanged()
                }
            }
        }
    }

    private fun onAuthorizationChanged() {
        val pending = authCallbacks.toList()
        authCallbacks.clear()
        pending.forEach { it() }
        PermissionRefreshObserver.notifyChanged()
    }

    private fun ensureResumeObserver() {
        if (resumeObserver != null) return
        resumeObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidBecomeActiveNotification,
            null,
            NSOperationQueue.mainQueue,
        ) { _ ->
            val pending = resumeCallbacks.toList()
            resumeCallbacks.clear()
            pending.forEach { it() }
        }
    }

    private fun askedAlwaysBefore() = NSUserDefaults.standardUserDefaults.boolForKey(KEY_ASKED_ALWAYS)
    private fun markAskedAlways() = NSUserDefaults.standardUserDefaults.setBool(true, forKey = KEY_ASKED_ALWAYS)
}

/** CLLocationManager keeps only an unretained delegate reference: [IosPermissions] owns this object. */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private class AuthDelegate(private val onChange: () -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
    // iOS 14+: also called once right after the delegate is set.
    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) = onChange()
}
