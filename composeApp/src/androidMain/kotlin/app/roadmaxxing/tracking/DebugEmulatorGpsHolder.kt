package app.roadmaxxing.tracking

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.location.Location
import android.location.LocationManager
import android.util.Log
import app.roadmaxxing.shared.drive.TrackingStatus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * DEBUG ONLY, EMULATOR TEST WORKAROUND. Does nothing in release builds (debuggable check + the receiver is
 * declared only in the debug manifest) and is off by default in debug builds.
 *
 * Why: the emulator has no Wi-Fi/cell positioning and its GPS HAL only reports `adb emu geo fix` positions while
 * some app requests GPS. With the production idle setup (activity transitions + stationary geofence, no GPS)
 * nothing requests GPS, so Play services never sees the replayed track (`gps provider: ProviderRequest[OFF]`).
 * Even with GPS held, Play services evaluates geofences at most every 5 min and suspends that while its
 * stationary detector (activity recognition + significant-motion sensor, both static on the emulator) says
 * "stationary" (see the GMS location dump in the B2 report).
 *
 * Modes (from the host; `adb shell am broadcast -n app.roadmaxxing/app.roadmaxxing.tracking.DebugGpsHolderReceiver ...`):
 * - `--ez enabled true`: holds a platform GPS request (2 s) so Play services sees the replayed positions;
 *   fixes go to a sink action and never wake the app (production trigger path = geofence).
 * - `--ez enabled true --ez fastTrigger true`: fixes are delivered to this receiver; a fix at vehicle speed
 *   (>= 6 m/s) while idle starts the probe service directly, standing in for the throttled geofence exit.
 *   Starting a foreground service from the background then needs the app exempt from battery optimisation:
 *   `adb shell dumpsys deviceidle whitelist +app.roadmaxxing`.
 * - `--ez enabled false`: removes the request.
 * Also needed on the emulator: `adb shell settings put global location_background_throttle_package_whitelist app.roadmaxxing`.
 */
class DebugGpsHolderReceiver : BroadcastReceiver(), KoinComponent {

    private val engine: TrackingEngine by inject()

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        if (intent.action == ACTION_FIX) {
            onFix(context, intent)
            return
        }
        val enabled = intent.getBooleanExtra("enabled", false)
        val fast = intent.getBooleanExtra("fastTrigger", false)
        val lm = context.getSystemService(LocationManager::class.java)
        // Remove both variants, then register the requested one. LocationManager requires mutable intents.
        lm.removeUpdates(sinkIntent(context))
        lm.removeUpdates(fixIntent(context))
        if (enabled) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, if (fast) fixIntent(context) else sinkIntent(context))
        }
        Log.w(TAG, "DEBUG emulator GPS holder ${if (enabled) "ENABLED" else "disabled"} fastTrigger=$fast")
    }

    private fun onFix(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val loc = intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED) ?: return
        if (!loc.hasSpeed() || loc.speed < FAST_TRIGGER_SPEED_MPS) return
        if (engine.recorder.isRecording || engine.status.status.value !is TrackingStatus.Idle) return
        Log.w(TAG, "DEBUG fast trigger: vehicle speed ${loc.speed} m/s while idle -> probe")
        DriveTrackingService.start(context, DriveTrackingService.MODE_PROBE)
    }

    private fun sinkIntent(context: Context) = PendingIntent.getBroadcast(
        context, 77, Intent(ACTION_SINK).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun fixIntent(context: Context) = PendingIntent.getBroadcast(
        context, 78, Intent(context, DebugGpsHolderReceiver::class.java).setAction(ACTION_FIX),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val TAG = "RmxDebugGps"
        const val ACTION_SINK = "app.roadmaxxing.debug.GPS_HOLDER_SINK"
        const val ACTION_FIX = "app.roadmaxxing.debug.GPS_HOLDER_FIX"
        const val FAST_TRIGGER_SPEED_MPS = 6f
    }
}
