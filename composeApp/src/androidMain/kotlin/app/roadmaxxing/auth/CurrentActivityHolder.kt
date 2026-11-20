package app.roadmaxxing.auth

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** Tracks the resumed Activity so Credential Manager has a UI context, without touching MainActivity. */
class CurrentActivityHolder(application: Application) {
    @Volatile
    private var ref: WeakReference<Activity>? = null

    val activity: Activity? get() = ref?.get()

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ref = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (ref?.get() === activity) ref = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
