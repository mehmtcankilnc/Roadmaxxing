package app.roadmaxxing.shared.domain

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.model.TracePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Test double of [DriveStore] with the same semantics as the SQL implementation. */
class InMemoryDriveStore : DriveStore {
    private val drives = MutableStateFlow<Map<Long, DriveRecord>>(emptyMap())
    private val pointsByDrive = mutableMapOf<Long, MutableMap<Long, TracePoint>>()
    private val segments = MutableStateFlow<Map<Long, Pair<Long, Long>>>(emptyMap())
    private var nextId = 1L

    val segmentFirstDrive: Map<Long, Long> get() = segments.value.mapValues { it.value.second }

    override fun createDrive(startMs: Long, startReason: String): Long {
        val id = nextId++
        drives.value += id to DriveRecord(id, startMs, startReason, DriveStatus.Recording, null, null, null, null, null)
        return id
    }

    override fun appendPoints(driveId: Long, points: List<TracePoint>) {
        val m = pointsByDrive.getOrPut(driveId) { mutableMapOf() }
        points.forEach { m.getOrPut(it.timeMs) { it } }
    }

    override fun markEnded(driveId: Long, decisionMs: Long, endReason: String, keepFromMs: Long, keepToMs: Long) {
        val d = drives.value[driveId] ?: return
        if (d.status != DriveStatus.Recording) return
        drives.value += driveId to d.copy(
            status = DriveStatus.Pending, endDecisionMs = decisionMs, endReason = endReason, keepFromMs = keepFromMs, keepToMs = keepToMs,
        )
    }

    override fun recordingDrive(): DriveRecord? = drives.value.values.filter { it.status == DriveStatus.Recording }.maxByOrNull { it.id }
    override fun lastPointTime(driveId: Long): Long? = pointsByDrive[driveId]?.keys?.maxOrNull()
    override fun pendingDrives(): List<DriveRecord> = drives.value.values.filter { it.status == DriveStatus.Pending }.sortedBy { it.id }
    override fun drive(driveId: Long): DriveRecord? = drives.value[driveId]
    fun allPoints(driveId: Long): List<TracePoint> = pointsByDrive[driveId].orEmpty().values.sortedBy { it.timeMs }
    fun allDrives(): List<DriveRecord> = drives.value.values.sortedBy { it.id }

    override fun points(driveId: Long, fromMs: Long, toMs: Long): List<TracePoint> =
        allPoints(driveId).filter { it.timeMs in fromMs..toMs }

    override fun completeProcessing(
        driveId: Long,
        drivenSegmentIds: Collection<Long>,
        firstDrivenMs: Long,
        summary: (newSegmentIds: Set<Long>) -> DriveSummary,
    ): DriveSummary {
        val newIds = drivenSegmentIds.filter { it !in segments.value }.toSet()
        segments.value += newIds.associateWith { firstDrivenMs to driveId }
        val s = summary(newIds)
        drives.value[driveId]?.let { drives.value += driveId to it.copy(status = DriveStatus.Processed, summary = s) }
        return s
    }

    override fun drivenSegmentIds(): Set<Long> = segments.value.keys
    override fun observeDrives(): Flow<List<DriveRecord>> = drives.map { m -> m.values.sortedByDescending { it.startMs } }
    override fun observeDrivenSegmentCount(): Flow<Long> = segments.map { it.size.toLong() }

    override fun drivenSegmentFirstDrives(): Map<Long, Long> = segments.value.mapValues { it.value.second }

    override fun segmentsFirstDrivenBy(driveId: Long): Set<Long> =
        segments.value.filterValues { it.second == driveId }.keys

    override fun segmentsFirstDrivenSince(sinceMs: Long): Set<Long> =
        segments.value.filterValues { it.first >= sinceMs }.keys

    override fun observeLastProcessedDriveId(): Flow<Long?> = drives.map { m ->
        m.values.filter { it.status == DriveStatus.Processed }
            .sortedWith(compareBy<DriveRecord>({ it.summary?.processedAtMs ?: 0L }, { it.id })).lastOrNull()?.id
    }
}
