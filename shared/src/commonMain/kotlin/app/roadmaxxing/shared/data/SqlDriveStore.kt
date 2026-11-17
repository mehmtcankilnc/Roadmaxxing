package app.roadmaxxing.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.roadmaxxing.shared.db.Drive
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.db.Trace_point
import app.roadmaxxing.shared.domain.DriveRecord
import app.roadmaxxing.shared.domain.DriveStatus
import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.model.TracePoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SqlDriveStore(
    private val database: RoadmaxxingDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) : DriveStore {

    private val drives get() = database.driveQueries
    private val tracePoints get() = database.tracePointQueries
    private val segments get() = database.drivenSegmentQueries

    override fun createDrive(startMs: Long, startReason: String): Long = database.transactionWithResult {
        drives.insertDrive(startMs, startReason)
        drives.lastInsertedId().executeAsOne()
    }

    override fun appendPoints(driveId: Long, points: List<TracePoint>) {
        if (points.isEmpty()) return
        database.transaction {
            for (p in points) {
                tracePoints.insertPoint(driveId, p.timeMs, p.lat, p.lon, p.speedMps, p.bearingDeg, p.accuracyM)
            }
        }
    }

    override fun markEnded(driveId: Long, decisionMs: Long, endReason: String, keepFromMs: Long, keepToMs: Long) {
        drives.markEnded(decisionMs, endReason, keepFromMs, keepToMs, driveId)
    }

    override fun recordingDrive(): DriveRecord? = drives.selectRecording().executeAsOneOrNull()?.toRecord()

    override fun lastPointTime(driveId: Long): Long? = tracePoints.lastForDrive(driveId).executeAsOneOrNull()?.time_ms

    override fun pendingDrives(): List<DriveRecord> = drives.selectPending().executeAsList().map { it.toRecord() }

    override fun drive(driveId: Long): DriveRecord? = drives.selectById(driveId).executeAsOneOrNull()?.toRecord()

    override fun points(driveId: Long, fromMs: Long, toMs: Long): List<TracePoint> =
        tracePoints.selectRange(driveId, fromMs, toMs).executeAsList().map { it.toTracePoint() }

    override fun completeProcessing(
        driveId: Long,
        drivenSegmentIds: Collection<Long>,
        firstDrivenMs: Long,
        summary: (newSegmentIds: Set<Long>) -> DriveSummary,
    ): DriveSummary = database.transactionWithResult {
        val newIds = mutableSetOf<Long>()
        for (id in drivenSegmentIds) {
            segments.insertIfNew(id, firstDrivenMs, driveId)
            if (segments.changes().executeAsOne() > 0L) newIds += id
        }
        val s = summary(newIds)
        drives.markProcessed(
            verdict = s.verdict.name,
            distance_m = s.distanceM,
            duration_ms = s.durationMs,
            point_count = s.pointCount.toLong(),
            matched_point_fraction = s.matchedPointFraction,
            driven_segment_count = s.drivenSegmentCount.toLong(),
            new_length_dm = s.newLengthDm,
            processing_ms = s.processingMs,
            processed_at_ms = s.processedAtMs,
            id = driveId,
        )
        s
    }

    override fun drivenSegmentIds(): Set<Long> = segments.selectAllIds().executeAsList().toSet()

    override fun drivenSegmentFirstDrives(): Map<Long, Long> =
        segments.selectAllWithFirstDrive().executeAsList().associate { it.segment_id to it.first_drive_id }

    override fun segmentsFirstDrivenBy(driveId: Long): Set<Long> =
        segments.selectFirstDrivenBy(driveId).executeAsList().toSet()

    override fun segmentsFirstDrivenSince(sinceMs: Long): Set<Long> =
        segments.selectFirstDrivenSince(sinceMs).executeAsList().toSet()

    override fun observeLastProcessedDriveId(): Flow<Long?> =
        drives.lastProcessedId().asFlow().mapToOneOrNull(ioDispatcher)

    override fun observeDrives(): Flow<List<DriveRecord>> =
        drives.selectAll().asFlow().mapToList(ioDispatcher).map { rows -> rows.map { it.toRecord() } }

    override fun observeDrivenSegmentCount(): Flow<Long> = segments.countAll().asFlow().mapToOne(ioDispatcher)
}

private fun Drive.toRecord(): DriveRecord = DriveRecord(
    id = id,
    startMs = start_ms,
    startReason = start_reason,
    status = when (status) {
        "RECORDING" -> DriveStatus.Recording
        "PENDING" -> DriveStatus.Pending
        else -> DriveStatus.Processed
    },
    endDecisionMs = end_decision_ms,
    endReason = end_reason,
    keepFromMs = keep_from_ms,
    keepToMs = keep_to_ms,
    summary = if (status != "PROCESSED" || verdict == null) {
        null
    } else {
        DriveSummary(
            verdict = DriveVerdict.entries.firstOrNull { it.name == verdict } ?: DriveVerdict.OffRoad,
            distanceM = distance_m ?: 0.0,
            durationMs = duration_ms ?: 0,
            pointCount = (point_count ?: 0).toInt(),
            matchedPointFraction = matched_point_fraction ?: 0.0,
            drivenSegmentCount = (driven_segment_count ?: 0).toInt(),
            newLengthDm = new_length_dm ?: 0,
            processingMs = processing_ms ?: 0,
            processedAtMs = processed_at_ms ?: 0,
        )
    },
)

private fun Trace_point.toTracePoint(): TracePoint = TracePoint(time_ms, lat, lon, speed_mps, bearing_deg, accuracy_m)
