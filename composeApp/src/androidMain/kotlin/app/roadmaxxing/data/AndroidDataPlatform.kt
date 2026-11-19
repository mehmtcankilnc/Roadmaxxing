package app.roadmaxxing.data

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import app.roadmaxxing.tracking.TrackingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/** Android side of [DataActions]: FileProvider share, tracking shutdown, notification and cache cleanup. */
internal class AndroidDataPlatform(
    context: Context,
    private val engine: TrackingEngine,
) : DataPlatform {
    private val app = context.applicationContext

    override fun openExportSink(): ExportSink {
        val dir = File(app.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // old exports must not pile up
        return FileSink(File(dir, TEMP_NAME))
    }

    override suspend fun shareExport(sink: ExportSink, fileName: String): Boolean {
        val temp = (sink as FileSink).file
        val uri = withContext(Dispatchers.IO) {
            val out = File(temp.parentFile, safeName(fileName))
            if (!temp.renameTo(out)) return@withContext null
            FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", out)
        } ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = GPX_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Targets that do not know the GPX type still accept a generic binary file.
        val hasGpxTarget = app.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        val chosen = if (hasGpxTarget) send else Intent(send).setType(FALLBACK_MIME)
        val chooser = Intent.createChooser(chosen, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(chooser) }.isSuccess
    }

    /** Buffered UTF-8 writer over a cache file. */
    private class FileSink(val file: File) : ExportSink {
        private val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), BUFFER_CHARS)
        private var closed = false

        override fun write(chunk: CharSequence) {
            writer.append(chunk)
        }

        override fun close() {
            if (closed) return
            closed = true
            writer.close()
        }

        override fun discard() {
            runCatching { close() }
            file.delete()
        }
    }

    override suspend fun stopRecordingForErase() = engine.stopAndDiscard()

    override suspend fun resumeTracking() = engine.resumeAfterDiscard()

    override fun cancelNotifications() {
        // The foreground recording notification is already gone: the service was stopped first.
        app.getSystemService(NotificationManager::class.java).cancelAll()
    }

    override fun deleteCachedFiles() {
        File(app.cacheDir, EXPORT_DIR).deleteRecursively()
        File(app.cacheDir, "share_cards").deleteRecursively()
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "roadmaxxing.gpx" }

    private companion object {
        const val EXPORT_DIR = "exports"
        const val TEMP_NAME = "export.tmp"
        const val BUFFER_CHARS = 64 * 1024
        const val GPX_MIME = "application/gpx+xml"
        const val FALLBACK_MIME = "application/octet-stream"
    }
}
