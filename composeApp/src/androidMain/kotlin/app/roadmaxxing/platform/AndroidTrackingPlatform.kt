package app.roadmaxxing.platform

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.roadmaxxing.tracking.DriveTrackingService
import app.roadmaxxing.tracking.TrackingEngine
import kotlinx.coroutines.launch

class AndroidTrackingPlatform(
    private val context: Context,
    private val engine: TrackingEngine,
) : TrackingPlatform {

    override val isDebugBuild: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun permissionStates(): Map<AppPermission, PermissionState> = AppPermission.entries.associateWith { p ->
        val name = p.manifestName() ?: return@associateWith PermissionState.NotNeeded
        if (context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) PermissionState.Granted else PermissionState.Denied
    }

    override fun onPermissionsChanged() {
        engine.scope.launch { engine.armIdleTriggers() }
    }

    override fun manualStart() {
        DriveTrackingService.start(context, DriveTrackingService.MODE_MANUAL)
    }

    override fun manualStop() {
        DriveTrackingService.manualStop(context)
    }
}

/** Android permission name, or null when the OS version does not have (or need) it. */
internal fun AppPermission.manifestName(): String? = when (this) {
    AppPermission.Location -> Manifest.permission.ACCESS_FINE_LOCATION
    AppPermission.BackgroundLocation ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACCESS_BACKGROUND_LOCATION else null
    AppPermission.ActivityRecognition ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION else null
    AppPermission.Notifications ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
}

@Composable
actual fun rememberPermissionRequester(onResult: () -> Unit): (AppPermission) -> Unit {
    // Location is asked as fine + coarse together (Android 12+ lets the user pick approximate).
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    // Background location must be a separate request; on Android 11+ the system opens the settings page.
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    return remember(multi, single) {
        { permission ->
            when (permission) {
                AppPermission.Location -> multi.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
                else -> permission.manifestName()?.let { single.launch(it) } ?: onResult()
            }
        }
    }
}
