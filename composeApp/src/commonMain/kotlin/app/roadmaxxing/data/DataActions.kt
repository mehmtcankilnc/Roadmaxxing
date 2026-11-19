package app.roadmaxxing.data

import app.roadmaxxing.notify.PendingDriveNavigation
import app.roadmaxxing.shared.domain.currentTimeMs
import app.roadmaxxing.shared.settings.DataEraser
import app.roadmaxxing.shared.settings.DataExporter
import kotlinx.coroutines.CancellationException

/** Outcome of [DataActions.exportAndShare]. */
enum class ExportResult { Shared, NothingToExport, Failed }

/** User-facing data controls: export all drives as GPX, or erase everything. */
interface DataActions {
    /** Streams one GPX with every drive into a file and opens the system share sheet. */
    suspend fun exportAndShare(): ExportResult

    /** Irreversible: stops recording, wipes all local data. The app returns to onboarding afterwards. */
    suspend fun eraseEverything()
}

/** Streaming target for the GPX text: a temporary file the exporter appends to chunk by chunk. */
interface ExportSink {
    /** Appends [chunk] (UTF-8). Throws on an I/O error. */
    fun write(chunk: CharSequence)

    /** Flushes and closes the file; it is complete afterwards. */
    fun close()

    /** Closes (if still open) and deletes the file. Never throws. */
    fun discard()
}

/** The platform half of [DataActions]: share sheet, tracking shutdown, notifications and cached files. */
interface DataPlatform {
    /** Deletes old export files and opens a fresh temporary sink. */
    fun openExportSink(): ExportSink

    /** Gives the closed [sink] file the name [fileName] and opens the system share sheet. False when that failed. */
    suspend fun shareExport(sink: ExportSink, fileName: String): Boolean

    /** Stops GPS and any open recording without matching it, and blocks new recordings until [resumeTracking]. */
    suspend fun stopRecordingForErase()

    /** Re-enables tracking and re-arms the idle triggers. */
    suspend fun resumeTracking()

    /** Removes every notification this app posted or scheduled. */
    fun cancelNotifications()

    /** Deletes cached card images and export files. */
    fun deleteCachedFiles()
}

internal class DefaultDataActions(
    private val exporter: DataExporter,
    private val eraser: DataEraser,
    private val platform: DataPlatform,
    private val navigation: PendingDriveNavigation,
) : DataActions {

    override suspend fun exportAndShare(): ExportResult {
        val sink = try {
            platform.openExportSink()
        } catch (e: Exception) {
            return ExportResult.Failed
        }
        val summary = try {
            exporter.exportGpx(currentTimeMs()) { sink.write(it) }
        } catch (e: Throwable) {
            sink.discard()
            if (e is CancellationException) throw e
            return ExportResult.Failed
        }
        if (summary == null) {
            sink.discard()
            return ExportResult.NothingToExport
        }
        val shared = try {
            sink.close()
            platform.shareExport(sink, summary.fileName)
        } catch (e: Exception) {
            sink.discard()
            false
        }
        return if (shared) ExportResult.Shared else ExportResult.Failed
    }

    override suspend fun eraseEverything() {
        platform.stopRecordingForErase()
        try {
            platform.cancelNotifications()
            platform.deleteCachedFiles()
            eraser.eraseAll()
            navigation.driveId.value?.let { navigation.consume(it) }
        } finally {
            // Even when the erase failed the app must not stay without tracking.
            platform.resumeTracking()
        }
    }
}
