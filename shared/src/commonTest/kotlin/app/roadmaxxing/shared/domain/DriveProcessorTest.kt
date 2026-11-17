package app.roadmaxxing.shared.domain

import app.roadmaxxing.shared.drive.DriveRecorder
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.matching.MatchResult
import app.roadmaxxing.shared.matching.MatchedSegment
import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.region.RoadLayer
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveProcessorTest {

    private fun seg(id: Long, lengthDm: Long) = MatchedSegment(id, 0.9, lengthDm, RoadLayer.Major, 1, 11)

    private fun drivingTrack(startMs: Long = 0): List<TracePoint> {
        val pts = mutableListOf<TracePoint>()
        var lat = 37.0
        for (i in 1..300) {
            lat += 12.0 / 111_195.0
            pts += TracePoint(startMs + i * 1000L, lat, 35.3, 12.0, null, 5.0)
        }
        for (i in 301..640) pts += TracePoint(startMs + i * 1000L, lat, 35.3, 0.0, null, 5.0)
        return pts
    }

    private fun record(store: InMemoryDriveStore, track: List<TracePoint>): Long {
        val recorder = DriveRecorder(store)
        var ended: Long? = null
        track.forEach { p -> recorder.onLocation(p).endedDriveId?.let { ended = it } }
        return assertNotNull(ended)
    }

    @Test
    fun drivenSegmentsAreStoredOnceAndNewLengthOnlyCountsFirstTime() = runTest {
        val store = InMemoryDriveStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val matched = listOf(seg(1, 1000), seg(2, 500))
        val processor = DriveProcessor(
            store = store,
            matcher = { TraceMatcher { MatchResult(matched, emptyList(), 0.97, 3600.0) } },
            ioDispatcher = dispatcher,
            cpuDispatcher = dispatcher,
            nowMs = { 42 },
        )
        val first = record(store, drivingTrack())
        processor.processPending()
        val s1 = assertNotNull(store.drive(first)?.summary)
        assertEquals(DriveVerdict.Drive, s1.verdict)
        assertEquals(1500, s1.newLengthDm)
        assertEquals(setOf(1L, 2L), store.drivenSegmentIds())

        val second = record(store, drivingTrack(startMs = 10_000_000))
        processor.processPending()
        val s2 = assertNotNull(store.drive(second)?.summary)
        assertEquals(0, s2.newLengthDm, "same road again adds nothing")
        assertEquals(first, store.segmentFirstDrive[1L])
    }

    @Test
    fun offRoadTraceWritesNoSegments() = runTest {
        val store = InMemoryDriveStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val processor = DriveProcessor(
            store = store,
            matcher = { TraceMatcher { MatchResult(listOf(seg(9, 100)), emptyList(), 0.1, 50.0) } },
            ioDispatcher = dispatcher,
            cpuDispatcher = dispatcher,
        )
        val id = record(store, drivingTrack())
        processor.processPending()
        assertEquals(DriveVerdict.OffRoad, store.drive(id)?.summary?.verdict)
        assertTrue(store.drivenSegmentIds().isEmpty())
    }

    @Test
    fun trimmedStationaryTailIsNotPartOfTheDrive() = runTest {
        val store = InMemoryDriveStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var matchedSize = 0
        val processor = DriveProcessor(
            store = store,
            matcher = {
                TraceMatcher { trace ->
                    matchedSize = trace.size
                    MatchResult(emptyList(), emptyList(), 0.97, 0.0)
                }
            },
            ioDispatcher = dispatcher,
            cpuDispatcher = dispatcher,
        )
        val id = record(store, drivingTrack())
        processor.processPending()
        assertTrue(matchedSize in 295..301, "matched $matchedSize points")
        assertTrue(store.allPoints(id).size > matchedSize, "raw trace keeps the tail")
    }

    @Test
    fun recorderRestoresAnOpenDriveAfterProcessDeath() {
        val store = InMemoryDriveStore()
        val first = DriveRecorder(store)
        drivingTrack().take(200).forEach { first.onLocation(it) }
        val open = assertNotNull(store.recordingDrive())
        val second = DriveRecorder(store)
        assertEquals(open.id, second.restore())
        assertNull(second.onTick(200_000 + 299_000).endedDriveId)
        assertEquals(open.id, second.onTick(200_000 + 300_000).endedDriveId)
    }
}
