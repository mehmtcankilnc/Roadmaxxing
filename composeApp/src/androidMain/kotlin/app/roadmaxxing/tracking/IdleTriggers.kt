package app.roadmaxxing.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority


/**
 * Low-power triggers that are armed while no drive is recorded. Neither keeps GPS on:
 *
 * - Activity Recognition transitions (IN_VEHICLE, ON_FOOT, ...): motion-sensor based, delivered as a broadcast.
 * - A "stationary geofence" (EXIT, [GEOFENCE_RADIUS_M]) around the last known position: evaluated by Play
 *   services with cell/Wi-Fi/sensor batching. Leaving it starts a short high-accuracy probe (speed start path).
 *
 * Both are exemptions from the Android 12+ ban on starting foreground services from the background
 * (developer.android.com, "Restrictions on starting a foreground service from the background").
 */
class IdleTriggers(private val context: Context) {

    private val app = context.applicationContext

    fun hasFineLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasActivityRecognition() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) granted(Manifest.permission.ACTIVITY_RECOGNITION) else true

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Arms activity transitions. Safe to call repeatedly (same PendingIntent replaces the old request). */
    @SuppressLint("MissingPermission")
    suspend fun armActivityTransitions(): Boolean {
        if (!hasActivityRecognition()) return false
        val types = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.ON_FOOT,
            DetectedActivity.WALKING,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL,
        )
        val transitions = types.flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { t ->
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(t).build()
            }
        }
        return runCatching {
            ActivityRecognition.getClient(app).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), activityIntent()).await()
        }.onFailure { Log.w(TAG, "activity transitions not armed", it) }.isSuccess
    }

    /**
     * Arms the stationary geofence around ([lat], [lon]), or around the last known position when null.
     * Needs background location: geofences only fire in the background with "Allow all the time".
     */
    @SuppressLint("MissingPermission")
    suspend fun armGeofence(lat: Double? = null, lon: Double? = null): Boolean {
        if (!hasFineLocation() || !hasBackgroundLocation()) return false
        val center = if (lat != null && lon != null) {
            lat to lon
        } else {
            val client = LocationServices.getFusedLocationProviderClient(app)
            val loc = runCatching { client.lastLocation.await() }.getOrNull()
                ?: runCatching { client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await() }.getOrNull()
                ?: return false
            loc.latitude to loc.longitude
        }
        val fence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(center.first, center.second, GEOFENCE_RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(GEOFENCE_RESPONSIVENESS_MS)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // no immediate trigger: we are inside by construction
            .addGeofence(fence)
            .build()
        return runCatching {
            LocationServices.getGeofencingClient(app).addGeofences(request, geofenceIntent()).await()
        }.onFailure { Log.w(TAG, "geofence not armed", it) }.isSuccess.also {
            if (it) Log.i(TAG, "geofence armed at %.5f,%.5f r=%.0f".format(center.first, center.second, GEOFENCE_RADIUS_M))
        }
    }

    suspend fun disarmGeofence() {
        runCatching { LocationServices.getGeofencingClient(app).removeGeofences(geofenceIntent()).await() }
    }

    // FLAG_MUTABLE is required: Play services adds the result extras to these intents.
    private fun activityIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, REQ_ACTIVITY,
        Intent(app, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_ACTIVITY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun geofenceIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, REQ_GEOFENCE,
        Intent(app, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_GEOFENCE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    companion object {
        private const val TAG = "RmxTriggers"
        private const val GEOFENCE_ID = "stationary"
        private const val REQ_ACTIVITY = 1
        private const val REQ_GEOFENCE = 2

        /** Large enough that GPS/Wi-Fi jitter of a phone at rest does not exit it; a car leaves it within a minute. */
        const val GEOFENCE_RADIUS_M = 200f

        /** 0 = report as fast as Play services can (Phase 0 measures the latency; raise to save battery). */
        const val GEOFENCE_RESPONSIVENESS_MS = 0
    }
}
