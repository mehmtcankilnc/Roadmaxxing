package app.roadmaxxing.data

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import app.roadmaxxing.tracking.IosTrackingEngine
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.writeData
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UserNotifications.UNUserNotificationCenter

/** iOS side of [DataActions]: share sheet, tracking shutdown, notification and temporary-file cleanup. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosDataPlatform(
    private val engine: IosTrackingEngine,
) : DataPlatform {

    override fun openExportSink(): ExportSink {
        deleteFilesWithExtension("gpx") // old exports must not pile up
        val path = NSTemporaryDirectory() + TEMP_NAME
        val files = NSFileManager.defaultManager
        files.removeItemAtPath(path, error = null)
        check(files.createFileAtPath(path, contents = null, attributes = null)) { "Cannot create export file" }
        val handle = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(path)) { "Cannot open export file" }
        return FileSink(path, handle)
    }

    override suspend fun shareExport(sink: ExportSink, fileName: String): Boolean = withContext(Dispatchers.Main) {
        val temp = (sink as FileSink).path
        val path = NSTemporaryDirectory() + safeName(fileName)
        val files = NSFileManager.defaultManager
        files.removeItemAtPath(path, error = null)
        if (!files.moveItemAtPath(temp, toPath = path, error = null)) return@withContext false
        val top = topViewController() ?: return@withContext false
        val controller = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
        // iPad presents the sheet as a popover and crashes without an anchor; centre it on the screen.
        controller.popoverPresentationController?.let { popover ->
            popover.sourceView = top.view
            val (w, h) = top.view.bounds.useContents { size.width to size.height }
            popover.sourceRect = CGRectMake(w / 2.0, h / 2.0, 1.0, 1.0)
            popover.permittedArrowDirections = 0uL
        }
        top.presentViewController(controller, animated = true, completion = null)
        true
    }

    /** Appends UTF-8 chunks to a temporary file, flushing a 64k-character buffer at a time. */
    private class FileSink(val path: String, private val handle: NSFileHandle) : ExportSink {
        private val buffer = StringBuilder()
        private var closed = false

        override fun write(chunk: CharSequence) {
            buffer.append(chunk)
            if (buffer.length >= BUFFER_CHARS) flush()
        }

        private fun flush() {
            if (buffer.isEmpty()) return
            val data = NSString.create(string = buffer.toString()).dataUsingEncoding(NSUTF8StringEncoding)
            buffer.clear()
            if (data != null) handle.writeData(data)
        }

        override fun close() {
            if (closed) return
            closed = true
            flush()
            handle.closeFile()
        }

        override fun discard() {
            if (!closed) {
                closed = true
                handle.closeFile()
            }
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    override suspend fun stopRecordingForErase() = engine.stopAndDiscard()

    override suspend fun resumeTracking() = engine.resumeAfterDiscard()

    override fun cancelNotifications() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        center.removeAllDeliveredNotifications()
        center.removeAllPendingNotificationRequests()
    }

    override fun deleteCachedFiles() {
        deleteFilesWithExtension("png")
        deleteFilesWithExtension("gpx")
        NSFileManager.defaultManager.removeItemAtPath(NSTemporaryDirectory() + TEMP_NAME, error = null)
    }

    private fun deleteFilesWithExtension(extension: String) {
        val manager = NSFileManager.defaultManager
        val dir = NSTemporaryDirectory()
        val names = manager.contentsOfDirectoryAtPath(dir, error = null) ?: return
        for (name in names) {
            val fileName = name as? String ?: continue
            if (fileName.endsWith(".$extension", ignoreCase = true)) manager.removeItemAtPath(dir + fileName, error = null)
        }
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "roadmaxxing.gpx" }

    private fun topViewController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }

    private companion object {
        const val TEMP_NAME = "export.tmp"
        const val BUFFER_CHARS = 64 * 1024
    }
}
