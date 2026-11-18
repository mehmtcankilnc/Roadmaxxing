package app.roadmaxxing.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.notification_result_channel
import org.jetbrains.compose.resources.getString

/** Android end-of-drive notification: own "Drive results" channel, tap opens the app on that drive's summary. */
class AndroidDriveResultPoster(context: Context) : DriveResultPoster {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    override suspend fun canPost(): Boolean = manager.areNotificationsEnabled()

    override suspend fun post(driveId: Long, title: String, body: String) {
        val channelName = getString(Res.string.notification_result_channel)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openSummaryIntent(driveId))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        // One notification per drive: the id is derived from the drive id.
        manager.notify(NOTIFICATION_ID_BASE + (driveId % ID_RANGE).toInt(), notification)
    }

    private fun openSummaryIntent(driveId: Long): PendingIntent? {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        val intent = Intent(DriveNotificationIntent.ACTION_OPEN_DRIVE)
            .setComponent(launch.component)
            .putExtra(DriveNotificationIntent.EXTRA_DRIVE_ID, driveId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // The request code must differ per drive, otherwise FLAG_UPDATE_CURRENT would overwrite another drive's extra.
        return PendingIntent.getActivity(
            app,
            (driveId % ID_RANGE).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val CHANNEL_ID = "drive_results"
        const val NOTIFICATION_ID_BASE = 2000
        const val ID_RANGE = 100_000L
    }
}
