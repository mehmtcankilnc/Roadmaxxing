package app.roadmaxxing.shared.data

import app.roadmaxxing.shared.domain.DriveRecord
import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.model.TracePoint
import kotlinx.coroutines.flow.Flow

/**
 * Persistence of drives, their raw fixes and driven segments. Synchronous and blocking: call it off the main
 * thread. [SqlDriveStore] is the real implementation; tests use an in-memory fake.
 */
interface DriveStore {
    fun createDrive(startMs: Long, startReason: String): Long
    fun appendPoints(driveId: Long, points: List<TracePoint>)
    fun markEnded(driveId: Long, decisionMs: Long, endReason: String, keepFromMs: Long, keepToMs: Long)
    fun recordingDrive(): DriveRecord?
    fun lastPointTime(driveId: Long): Long?
    fun pendingDrives(): List<DriveRecord>
    fun drive(driveId: Long): DriveRecord?
    fun points(driveId: Long, fromMs: Long, toMs: Long): List<TracePoint>

    /**
     * Stores the result of processing in one transaction: the drive summary and every segment not driven before.
     * [summary] receives the ids that were new (not driven before) and builds the stored summary.
     */
    fun completeProcessing(
        driveId: Long,
        drivenSegmentIds: Collection<Long>,
        firstDrivenMs: Long,
        summary: (newSegmentIds: Set<Long>) -> DriveSummary,
    ): DriveSummary

    fun drivenSegmentIds(): Set<Long>

    /** Every driven segment id mapped to the drive that first drove it. */
    fun drivenSegmentFirstDrives(): Map<Long, Long>

    /** Segments first driven by [driveId] (the "fresh" roads of that drive). */
    fun segmentsFirstDrivenBy(driveId: Long): Set<Long>

    /** Emits the full drive list (newest first) whenever it changes. */
    fun observeDrives(): Flow<List<DriveRecord>>

    /** Emits a counter that changes whenever driven segments are added. */
    fun observeDrivenSegmentCount(): Flow<Long>

    /** Emits the id of the most recently processed drive (null when none) whenever it changes. */
    fun observeLastProcessedDriveId(): Flow<Long?>
}
