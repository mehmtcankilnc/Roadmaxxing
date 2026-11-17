package app.roadmaxxing.share

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.writeToFile
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIViewController

@Composable
actual fun rememberCardSharer(): CardSharer = remember { IosCardSharer() }

actual fun deviceLanguageCode(): String = NSLocale.currentLocale.languageCode

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosCardSharer : CardSharer {

    override fun share(png: ByteArray, fileName: String) {
        val path = NSTemporaryDirectory() + fileName
        if (!png.toNSData().writeToFile(path, atomically = true)) return
        val top = topViewController() ?: return
        val controller = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
        // iPad presents the sheet as a popover and crashes without an anchor; centre it on the screen.
        controller.popoverPresentationController?.let { popover ->
            popover.sourceView = top.view
            val (w, h) = top.view.bounds.useContents { size.width to size.height }
            popover.sourceRect = CGRectMake(w / 2.0, h / 2.0, 1.0, 1.0)
            popover.permittedArrowDirections = 0u
        }
        top.presentViewController(controller, animated = true, completion = null)
    }

    override suspend fun saveToGallery(png: ByteArray, fileName: String): Result<Unit> {
        val granted = suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
                cont.resume(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited)
            }
        }
        if (!granted) return Result.failure(SaveFailure.PermissionDenied())
        val image = UIImage.imageWithData(png.toNSData()) ?: return Result.failure(IllegalStateException("Invalid PNG"))
        return suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                changeBlock = { PHAssetChangeRequest.creationRequestForAssetFromImage(image) },
                completionHandler = { success, error ->
                    cont.resume(
                        if (success) Result.success(Unit)
                        else Result.failure(IllegalStateException(error?.localizedDescription ?: "Photo library error")),
                    )
                },
            )
        }
    }

    private fun ByteArray.toNSData(): NSData =
        if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

    private fun topViewController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
