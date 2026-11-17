package app.roadmaxxing.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.roadmaxxing.shared.drive.ActivityType
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Receives activity transitions and geofence events (both may arrive while the process was dead). */
class TriggerReceiver : BroadcastReceiver(), KoinComponent {

    private val engine: TrackingEngine by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        engine.scope.launch {
            try {
                when (intent.action) {
                    ACTION_ACTIVITY -> handleActivity(intent)
                    ACTION_GEOFENCE -> handleGeofence(intent)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleActivity(intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (event in result.transitionEvents) {
            if (event.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) continue
            val type = when (event.activityType) {
                DetectedActivity.IN_VEHICLE -> ActivityType.InVehicle
                DetectedActivity.STILL -> ActivityType.Still
                DetectedActivity.ON_FOOT, DetectedActivity.WALKING, DetectedActivity.RUNNING -> ActivityType.OnFoot
                DetectedActivity.ON_BICYCLE -> ActivityType.OnBicycle
                else -> ActivityType.Unknown
            }
            // elapsedRealTimeNanos -> wall clock
            val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - event.elapsedRealTimeNanos) / 1_000_000
            val timeMs = System.currentTimeMillis() - ageMs.coerceAtLeast(0)
            Log.i(TAG, "activity enter $type")
            engine.onActivity(type, timeMs)
        }
    }

    private suspend fun handleGeofence(intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence error ${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val loc = event.triggeringLocation
        Log.i(TAG, "geofence exit at ${loc?.latitude},${loc?.longitude}")
        engine.onGeofenceExit(loc?.latitude, loc?.longitude, System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "RmxTrigger"
        const val ACTION_ACTIVITY = "app.roadmaxxing.action.ACTIVITY_TRANSITION"
        const val ACTION_GEOFENCE = "app.roadmaxxing.action.GEOFENCE"
    }
}

/** Geofences and activity transitions are dropped on reboot and app update: re-arm them (no service start). */
class BootReceiver : BroadcastReceiver(), KoinComponent {
    private val engine: TrackingEngine by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Application.onCreate already ran engine.onProcessStart(), which re-arms; keep the process alive for it.
        val pending = goAsync()
        engine.scope.launch {
            try {
                engine.armIdleTriggers()
            } finally {
                pending.finish()
            }
        }
    }
}
