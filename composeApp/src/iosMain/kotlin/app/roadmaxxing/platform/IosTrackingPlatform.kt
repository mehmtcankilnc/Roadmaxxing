package app.roadmaxxing.platform

import androidx.compose.runtime.Composable

// NOT VERIFIED: iosMain cannot be compiled on Windows. Minimal placeholder until the CoreLocation /
// CoreMotion work of the next round; it reports every permission as missing and does nothing.
class IosTrackingPlatform : TrackingPlatform {
    override val isDebugBuild: Boolean = false
    override fun permissionStates(): Map<AppPermission, PermissionState> =
        AppPermission.entries.associateWith { PermissionState.Denied }
    override fun onPermissionsChanged() = Unit
    override fun manualStart() = Unit
    override fun manualStop() = Unit
}

@Composable
actual fun rememberPermissionRequester(onResult: () -> Unit): (AppPermission) -> Unit = { onResult() }
