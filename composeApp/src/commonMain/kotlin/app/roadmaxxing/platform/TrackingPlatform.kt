package app.roadmaxxing.platform

import androidx.compose.runtime.Composable

/** Permissions the drive recording needs, in the order they are asked. */
enum class AppPermission {
    /** Precise location while the app is in use. */
    Location,

    /** "Allow all the time": needed to start recording from the background. */
    BackgroundLocation,

    /** Motion & fitness / physical activity: detects being in a vehicle without GPS. */
    ActivityRecognition,

    /** Optional: the recording notification. Recording works without it. */
    Notifications,
}

enum class PermissionState { Granted, Denied, NotNeeded }

/** Platform side of drive tracking as the UI sees it. */
interface TrackingPlatform {
    /** Debug builds show manual start/stop. */
    val isDebugBuild: Boolean

    fun permissionStates(): Map<AppPermission, PermissionState>

    /** Called after permissions changed: (re)arms the background triggers. */
    fun onPermissionsChanged()

    fun manualStart()
    fun manualStop()
}

/**
 * Returns a function that asks for one permission and calls [onResult] when the system dialog (or settings
 * screen) closes. Background location needs foreground location first.
 */
@Composable
expect fun rememberPermissionRequester(onResult: () -> Unit): (AppPermission) -> Unit
