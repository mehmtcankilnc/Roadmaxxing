package app.roadmaxxing.shared.drive

import app.roadmaxxing.shared.model.TracePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DriveDetectorTest {

    /** Builds a 1 Hz synthetic track heading north from a start point, segment by segment. */
    private class TrackBuilder(startLat: Double = 37.0, private val lon: Double = 35.3) {
        val points = mutableListOf<TracePoint>()
        private var t = 0L
        private var lat = startLat

        fun move(seconds: Int, speedMps: Double, reportSpeed: Boolean = true): TrackBuilder {
            repeat(seconds) {
                lat += speedMps / 111_195.0
                t += 1000
                points += TracePoint(t, lat, lon, if (reportSpeed) speedMps else null, null, 5.0)
            }
            return this
        }

        fun stop(seconds: Int) = move(seconds, 0.0)
    }

    private fun run(detector: DriveDetector, track: List<TracePoint>, activities: Map<Long, ActivityType> = emptyMap()): List<DriveEvent> {
        val events = mutableListOf<DriveEvent>()
        for (p in track) {
            activities[p.timeMs]?.let { events += detector.onActivity(it, p.timeMs) }
            events += detector.onLocation(p)
        }
        return events
    }

    private fun List<DriveEvent>.starts() = filterIsInstance<DriveEvent.DriveStarted>()
    private fun List<DriveEvent>.ends() = filterIsInstance<DriveEvent.DriveEnded>()

    @Test
    fun walkingNeverStartsADrive() {
        val track = TrackBuilder().move(1800, 1.4).stop(60).move(600, 1.6).points
        val events = run(DriveDetector(), track)
        assertTrue(events.starts().isEmpty())
    }

    @Test
    fun joggingNeverStartsADrive() {
        val track = TrackBuilder().move(1800, 3.5).points
        assertTrue(run(DriveDetector(), track).starts().isEmpty())
    }

    @Test
    fun walkingWithoutSpeedFieldNeverStartsADrive() {
        val track = TrackBuilder().move(1200, 1.4, reportSpeed = false).points
        assertTrue(run(DriveDetector(), track).starts().isEmpty())
    }

    @Test
    fun sustainedVehicleSpeedStartsADriveAndKeepsTheBufferedStart() {
        val track = TrackBuilder().stop(10).move(20, 4.0).move(60, 12.0).points
        val events = run(DriveDetector(), track)
        val start = events.starts().single()
        assertEquals(StartReason.Speed, start.reason)
        // The slow pull-away before the start decision belongs to the drive.
        assertTrue(start.initialPoints.first().timeMs <= 11_000, "buffer starts at ${start.initialPoints.first().timeMs}")
    }

    @Test
    fun aSingleSpeedGlitchDoesNotStartADrive() {
        val b = TrackBuilder().stop(30)
        b.points += b.points.last().copy(timeMs = 31_000, speedMps = 30.0)
        b.points += b.points.last().copy(timeMs = 32_000, speedMps = 0.0)
        assertTrue(run(DriveDetector(), b.points).starts().isEmpty())
    }

    @Test
    fun speedDerivedFromPositionsStartsADrive() {
        val track = TrackBuilder().stop(5).move(90, 12.0, reportSpeed = false).points
        assertEquals(1, run(DriveDetector(), track).starts().size)
    }

    @Test
    fun inVehicleActivityStartsImmediately() {
        val d = DriveDetector()
        val events = d.onActivity(ActivityType.InVehicle, 1_000)
        assertEquals(StartReason.Activity, assertIs<DriveEvent.DriveStarted>(events.single()).reason)
        assertTrue(d.isRecording)
    }

    @Test
    fun withoutActivitySignalFourMinuteStopKeepsOneDrive() {
        val track = TrackBuilder().move(300, 12.0).stop(240).move(300, 12.0).stop(320).points
        val events = run(DriveDetector(), track)
        assertEquals(1, events.starts().size)
        assertEquals(1, events.ends().size)
        assertEquals(EndReason.StillWithoutVehicleSignal, events.ends().single().reason)
    }

    @Test
    fun withoutActivitySignalSixAndHalfMinuteStopSplitsIntoTwoDrives() {
        val track = TrackBuilder().move(300, 12.0).stop(390).move(300, 12.0).stop(320).points
        val events = run(DriveDetector(), track)
        assertEquals(2, events.starts().size)
        assertEquals(2, events.ends().size)
    }

    @Test
    fun endTrimsTheStationaryTail() {
        val track = TrackBuilder().move(300, 12.0).stop(310).points
        val end = run(DriveDetector(), track).ends().single()
        assertEquals(300_000, end.keepToMs)
        assertEquals(600_000, end.decisionTimeMs)
    }

    @Test
    fun stillInsideTheCarDoesNotEndAfterThreeMinutes() {
        val track = TrackBuilder().move(300, 12.0).stop(400).points
        val events = run(DriveDetector(), track, mapOf(5_000L to ActivityType.InVehicle, 310_000L to ActivityType.Still))
        // 400 s stationary with STILL: below the 15 min fallback, the drive goes on.
        assertTrue(events.ends().isEmpty())
    }

    @Test
    fun onlyStillActivityBehavesLikeNoSignal() {
        // The emulator's motionless sensors report STILL: the 5 min rule applies, a 6.5 min stop splits.
        val track = TrackBuilder().move(300, 12.0).stop(390).move(300, 12.0).stop(320).points
        val events = run(DriveDetector(), track, mapOf(1_000L to ActivityType.Still, 320_000L to ActivityType.Still))
        assertEquals(2, events.starts().size)
        assertEquals(EndReason.StillWithoutVehicleSignal, events.ends().first().reason)
    }

    @Test
    fun onFootBeforeTheDriveDoesNotEndItAtThreeMinutes() {
        val track = TrackBuilder().stop(5).move(300, 12.0).stop(200).points
        val events = run(DriveDetector(), track, mapOf(2_000L to ActivityType.OnFoot))
        assertTrue(events.ends().isEmpty())
    }

    @Test
    fun onFootAfterThreeMinutesStationaryEndsTheDrive() {
        val track = TrackBuilder().move(300, 12.0).stop(200).points
        val events = run(DriveDetector(), track, mapOf(5_000L to ActivityType.InVehicle, 320_000L to ActivityType.OnFoot))
        val end = events.ends().single()
        assertEquals(EndReason.StillWithOutOfVehicleActivity, end.reason)
        assertEquals(480_000, end.decisionTimeMs)
    }

    @Test
    fun walkingAwayFromTheCarCountsAsStationaryForTheVehicle() {
        val track = TrackBuilder().move(300, 12.0).move(400, 1.4).points
        val events = run(DriveDetector(), track, mapOf(5_000L to ActivityType.InVehicle, 305_000L to ActivityType.OnFoot))
        assertEquals(EndReason.StillWithOutOfVehicleActivity, events.ends().single().reason)
    }

    @Test
    fun stuckStillActivityEndsAtTheFallback() {
        val track = TrackBuilder().move(300, 12.0).stop(16 * 60).points
        val events = run(DriveDetector(), track, mapOf(5_000L to ActivityType.InVehicle, 310_000L to ActivityType.Still))
        assertEquals(EndReason.StillFallback, events.ends().single().reason)
    }

    @Test
    fun tickEndsADriveWhenFixesStop() {
        val d = DriveDetector()
        run(d, TrackBuilder().move(120, 12.0).points)
        assertTrue(d.isRecording)
        assertTrue(d.onTick(120_000 + 4 * 60_000).isEmpty())
        assertIs<DriveEvent.DriveEnded>(d.onTick(120_000 + 5 * 60_000).single())
        assertFalse(d.isRecording)
    }

    @Test
    fun poorAccuracyAndOutOfOrderFixesAreDropped() {
        val d = DriveDetector()
        d.manualStart(0)
        val good = TracePoint(1_000, 37.0, 35.0, 10.0, null, 5.0)
        assertIs<DriveEvent.PointAccepted>(d.onLocation(good).first())
        val bad = assertIs<DriveEvent.PointDropped>(d.onLocation(good.copy(timeMs = 2_000, accuracyM = 120.0)).single())
        assertEquals(DropReason.PoorAccuracy, bad.reason)
        val old = assertIs<DriveEvent.PointDropped>(d.onLocation(good.copy(timeMs = 500)).single())
        assertEquals(DropReason.OutOfOrder, old.reason)
    }

    @Test
    fun resumedDriveEndsFromItsLastStoredPoint() {
        val d = DriveDetector()
        d.resumeRecording(startTimeMs = 0, lastPointTimeMs = 100_000)
        assertTrue(d.onTick(100_000 + 299_000).isEmpty())
        assertEquals(1, d.onTick(100_000 + 300_000).size)
    }

    @Test
    fun validatorRejectsShortSlowAndOffRoadTraces() {
        val v = DriveValidator()
        val car = TraceStats.of(TrackBuilder().move(600, 12.0).points)
        assertEquals(DriveVerdict.Drive, v.validate(car, matchedPointFraction = 0.95))
        assertEquals(DriveVerdict.OffRoad, v.validate(car, matchedPointFraction = 0.2))
        assertEquals(DriveVerdict.TooShort, v.preCheck(TraceStats.of(TrackBuilder().move(20, 12.0).points)))
        assertEquals(DriveVerdict.TooSlow, v.preCheck(TraceStats.of(TrackBuilder().move(100, 12.0).stop(1000).points)))
        assertEquals(DriveVerdict.TooFast, v.preCheck(TraceStats.of(TrackBuilder().move(600, 100.0).points)))
    }
}
