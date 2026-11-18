package app.roadmaxxing.notify

import android.content.Intent
import org.koin.mp.KoinPlatform

/** The intent contract between the end-of-drive notification and the activity. */
object DriveNotificationIntent {
    const val ACTION_OPEN_DRIVE = "app.roadmaxxing.action.OPEN_DRIVE"
    const val EXTRA_DRIVE_ID = "app.roadmaxxing.extra.DRIVE_ID"

    /**
     * Forwards a tapped end-of-drive notification to [PendingDriveNavigation]. Call from the activity's
     * onCreate (first creation only) and onNewIntent. Other intents are ignored.
     */
    fun handle(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_DRIVE) return
        val id = intent.getLongExtra(EXTRA_DRIVE_ID, -1L)
        if (id < 0) return
        KoinPlatform.getKoin().get<PendingDriveNavigation>().open(id)
    }
}
