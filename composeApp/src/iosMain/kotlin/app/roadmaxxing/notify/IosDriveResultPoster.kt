package app.roadmaxxing.notify

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSError
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * iOS end-of-drive notification through [UNUserNotificationCenter]. The notification delegate is written in
 * Kotlin (no Swift): it shows the banner in the foreground and forwards a tap to [PendingDriveNavigation].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDriveResultPoster(private val navigation: PendingDriveNavigation) : DriveResultPoster {
    // UNUserNotificationCenter.delegate is weak: this property keeps the delegate alive.
    private val delegate = Delegate(navigation)

    override fun install() {
        UNUserNotificationCenter.currentNotificationCenter().delegate = delegate
    }

    override suspend fun canPost(): Boolean = suspendCoroutine { cont ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            cont.resume(
                status == UNAuthorizationStatusAuthorized ||
                    status == UNAuthorizationStatusProvisional ||
                    status == UNAuthorizationStatusEphemeral,
            )
        }
    }

    override suspend fun post(driveId: Long, title: String, body: String) {
        val content = UNMutableNotificationContent()
        content.setTitle(title)
        content.setBody(body)
        content.setUserInfo(mapOf<Any?, Any?>(KEY_DRIVE_ID to driveId.toString()))
        // Same identifier per drive, so a repeated post replaces instead of duplicating. A null trigger delivers now.
        val request = UNNotificationRequest.requestWithIdentifier("drive-$driveId", content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { error: NSError? ->
            if (error != null) println("[RmxNotify] could not post notification: ${error.localizedDescription}")
        }
    }

    private class Delegate(private val navigation: PendingDriveNavigation) : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(UNNotificationPresentationOptionBanner)
        }

        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit,
        ) {
            val id = (didReceiveNotificationResponse.notification.request.content.userInfo[KEY_DRIVE_ID] as? String)?.toLongOrNull()
            if (id != null) navigation.open(id)
            withCompletionHandler()
        }
    }

    private companion object {
        const val KEY_DRIVE_ID = "driveId"
    }
}
