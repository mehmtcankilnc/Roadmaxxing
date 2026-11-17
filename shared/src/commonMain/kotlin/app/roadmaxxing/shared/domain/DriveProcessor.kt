package app.roadmaxxing.shared.domain

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.drive.DriveValidator
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.drive.TraceStats
import app.roadmaxxing.shared.matching.MatchResult
import app.roadmaxxing.shared.model.TracePoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** Matches a trace against the road network. Wraps B1's RoadMatcher so processing can be tested with fakes. */
fun interface TraceMatcher {
    fun match(trace: List<TracePoint>): MatchResult
}

/**
 * Turns finished (PENDING) drives into results: trims, validates, matches and stores newly driven segments.
 * Idempotent and restart safe: the trace is already in the database, a drive only leaves PENDING in the same
 * transaction that writes its segments, and segments are inserted once (re-driving a road adds nothing).
 */
class DriveProcessor(
    private val store: DriveStore,
    private val matcher: () -> TraceMatcher,
    private val validator: DriveValidator = DriveValidator(),
    private val ioDispatcher: CoroutineDispatcher,
    private val cpuDispatcher: CoroutineDispatcher,
    private val nowMs: () -> Long = ::currentTimeMs,
) {
    private val mutex = Mutex()

    /** Processes every pending drive. Returns the processed ids. */
    suspend fun processPending(): List<Long> = mutex.withLock {
        val pending = withContext(ioDispatcher) { store.pendingDrives() }
        pending.map { drive ->
            processOne(drive)
            drive.id
        }
    }

    private suspend fun processOne(drive: DriveRecord) {
        val mark = TimeSource.Monotonic.markNow()
        val from = drive.keepFromMs ?: drive.startMs
        val to = drive.keepToMs ?: Long.MAX_VALUE
        val points = withContext(ioDispatcher) { store.points(drive.id, from, to) }
        val stats = TraceStats.of(points)
        var verdict = validator.preCheck(stats)
        var match: MatchResult? = null
        if (verdict == DriveVerdict.Drive) {
            match = withContext(cpuDispatcher) { matcher().match(points) }
            verdict = validator.validate(stats, match.matchedPointFraction)
        }
        val driven = if (verdict == DriveVerdict.Drive) match?.driven.orEmpty() else emptyList()
        val lengthById = driven.associate { it.segmentId to it.lengthDm }
        withContext(ioDispatcher) {
            store.completeProcessing(drive.id, lengthById.keys, firstDrivenMs = from) { newIds ->
                DriveSummary(
                    verdict = verdict,
                    distanceM = stats.distanceM,
                    durationMs = stats.durationMs,
                    pointCount = stats.pointCount,
                    matchedPointFraction = match?.matchedPointFraction ?: 0.0,
                    drivenSegmentCount = driven.size,
                    newLengthDm = newIds.sumOf { lengthById[it] ?: 0L },
                    processingMs = mark.elapsedNow().inWholeMilliseconds,
                    processedAtMs = nowMs(),
                )
            }
        }
    }
}
