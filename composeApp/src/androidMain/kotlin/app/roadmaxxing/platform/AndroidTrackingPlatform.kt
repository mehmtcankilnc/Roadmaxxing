package app.roadmaxxing.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.roadmaxxing.tracking.DriveTrackingService
import app.roadmaxxing.tracking.TrackingEngine
import kotlinx.coroutines.launch

class AndroidTrackingPlatform(
    private val context: Context,
    private val engine: TrackingEngine,
) : TrackingPlatform {

    private val prefs = PermissionPrefs(context)

    override val isDebugBuild: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override fun permissionStates(): Map<AppPermission, PermissionState> = AppPermission.entries.associateWith { p ->
        when (p) {
            AppPermission.Location -> locationState()
            else -> {
                val name = p.manifestName() ?: return@associateWith PermissionState.NotNeeded
                simpleState(p, name)
            }
        }
    }

    private fun granted(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    private fun simpleState(p: AppPermission, name: String): PermissionState {
        if (granted(name)) {
            prefs.setBlocked(p, false)
            return PermissionState.Granted
        }
        // Background location is routed to system Settings on Android 11+: once asked, only Settings can grant it.
        val settingsOnly = p == AppPermission.BackgroundLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        return if (prefs.isBlocked(p) || (settingsOnly && prefs.wasAsked(p))) PermissionState.Blocked else PermissionState.Denied
    }

    private fun locationState(): PermissionState {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            fine -> {
                prefs.setBlocked(AppPermission.Location, false)
                PermissionState.Granted
            }
            coarse -> PermissionState.Approximate
            prefs.isBlocked(AppPermission.Location) -> PermissionState.Blocked
            else -> PermissionState.Denied
        }
    }

    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
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

/** Remembers which permissions were requested before and which ended up permanently denied. */
internal class PermissionPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("rmx_permissions", Context.MODE_PRIVATE)

    fun wasAsked(p: AppPermission) = prefs.getBoolean("asked_${p.name}", false)
    fun markAsked(p: AppPermission) = prefs.edit().putBoolean("asked_${p.name}", true).apply()
    fun isBlocked(p: AppPermission) = prefs.getBoolean("blocked_${p.name}", false)
    fun setBlocked(p: AppPermission, blocked: Boolean) {
        if (isBlocked(p) != blocked) prefs.edit().putBoolean("blocked_${p.name}", blocked).apply()
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
actual fun rememberPermissionRequester(onResult: () -> Unit): (AppPermission) -> Unit {
    val context = LocalContext.current
    val prefs = remember(context) { PermissionPrefs(context) }
    val current = rememberUpdatedState(onResult)
    // The permission that was just asked; set before launching so the result handler knows what to classify.
    val pending = remember { arrayOfNulls<AppPermission>(1) }

    fun finish() {
        val asked = pending[0]
        pending[0] = null
        if (asked != null) {
            val activity = context.findActivity()
            val names = if (asked == AppPermission.Location) {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            } else {
                listOfNotNull(asked.manifestName())
            }
            val anyGranted = names.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            // Denied and the system will not show its dialog again => only Settings can grant it.
            val canAskAgain = activity != null && names.any { activity.shouldShowRequestPermissionRationale(it) }
            prefs.setBlocked(asked, !anyGranted && !canAskAgain && activity != null)
        }
        PermissionRefreshObserver.notifyChanged()
        current.value()
    }

    // Location is asked as fine + coarse together (Android 12+ lets the user pick approximate).
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }
    // Background location must be a separate request; on Android 11+ the system opens the settings page.
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
    return remember(multi, single, context, prefs) {
        { permission ->
            val locationReady = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            // Background location may only be requested after foreground location: ask that first.
            val target = if (permission == AppPermission.BackgroundLocation && !locationReady) AppPermission.Location else permission
            val name = target.manifestName()
            if (name == null) {
                PermissionRefreshObserver.notifyChanged()
                current.value()
            } else {
                pending[0] = target
                prefs.markAsked(target)
                if (target == AppPermission.Location) {
                    multi.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    single.launch(name)
                }
            }
        }
    }
}
