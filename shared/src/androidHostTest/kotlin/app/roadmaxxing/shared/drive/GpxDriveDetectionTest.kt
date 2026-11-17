package app.roadmaxxing.shared.drive

import app.roadmaxxing.shared.model.TracePoint
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feeds the speed profiles of the GPX test routes (test-data/gpx) through [DriveDetector] the way the emulator
 * delivers them (1 Hz, no activity signal) and checks the number of recordings per scenario.
 */
class GpxDriveDetectionTest {

    /**
     * @param emulatorStill also deliver the STILL transitions the emulator's motionless sensors report
     *   (observed on the Pixel_8 emulator): at the start and every 5 minutes.
     */
    private fun detect(name: String, useSpeedField: Boolean = true, emulatorStill: Boolean = false): List<DriveEvent.DriveEnded> {
        val points = GpxReader.read(name).let { pts -> if (useSpeedField) pts else pts.map { it.copy(speedMps = null) } }
        val detector = DriveDetector()
        val ends = mutableListOf<DriveEvent.DriveEnded>()
        val t0 = points.first().timeMs
        for (p in points) {
            if (emulatorStill && (p.timeMs - t0) % 300_000L == 0L) {
                ends += detector.onActivity(ActivityType.Still, p.timeMs).filterIsInstance<DriveEvent.DriveEnded>()
            }
            ends += detector.onLocation(p).filterIsInstance<DriveEvent.DriveEnded>()
        }
        // After the track the phone stays put: keep the clock running for 10 minutes.
        val last = points.last().timeMs
        for (s in 1..600) ends += detector.onTick(last + s * 1000L).filterIsInstance<DriveEvent.DriveEnded>()
        return ends
    }

    private fun assertRecordings(expected: Int, name: String) {
        for (file in listOf(name, "$name.noisy")) {
            for (speedField in listOf(true, false)) {
                for (still in listOf(false, true)) {
                    val got = detect(file, speedField, still)
                    assertEquals(expected, got.size, "$file (speed field=$speedField, STILL=$still): ${got.map { it.keepFromMs to it.keepToMs }}")
                }
            }
        }
    }

    @Test fun mainRoadsIsOneDrive() = assertRecordings(1, "main-roads")
    @Test fun residentialIsOneDrive() = assertRecordings(1, "residential")
    @Test fun parallelRoadsIsOneDrive() = assertRecordings(1, "parallel-roads")
    @Test fun intercityIsOneDrive() = assertRecordings(1, "intercity")
    @Test fun trafficStopStaysOneDrive() = assertRecordings(1, "traffic-stop")
    @Test fun trafficStopLongSplitsIntoTwoDrives() = assertRecordings(2, "traffic-stop-long")
    @Test fun walkNeverStartsARecording() = assertRecordings(0, "not-a-drive")

    /** The train reaches vehicle speed, so it IS recorded; the matcher/validator rejects it afterwards. */
    @Test fun trainIsRecordedOnceAndPassesThePreCheck() = assertRecordings(1, "not-a-drive-train")

    @Test
    fun recordingCoversAlmostTheWholeDrive() {
        for (name in listOf("residential", "main-roads", "parallel-roads")) {
            val pts = GpxReader.read(name)
            val end = detect(name).single()
            val moving = pts.filter { (it.speedMps ?: 0.0) >= 2.5 }
            // The speed start path may skip at most the first ~10 s of pulling away.
            assertTrue(end.keepFromMs - moving.first().timeMs <= 15_000, "$name starts late: ${end.keepFromMs - moving.first().timeMs} ms")
            assertTrue(moving.last().timeMs - end.keepToMs <= 2_000, "$name ends early")
        }
    }
}

/** Minimal reader for the generated GPX 1.1 files (trkpt lat/lon, time, gpxtpx:speed). */
internal object GpxReader {
    private val trkpt = Regex("""<trkpt lat="([-0-9.]+)" lon="([-0-9.]+)">(.*?)</trkpt>""", RegexOption.DOT_MATCHES_ALL)
    private val time = Regex("<time>([^<]+)</time>")
    private val speed = Regex("<gpxtpx:speed>([-0-9.]+)</gpxtpx:speed>")

    fun file(name: String): File {
        val candidates = listOf(File("../test-data/gpx/$name.gpx"), File("test-data/gpx/$name.gpx"))
        return candidates.firstOrNull { it.exists() } ?: error("GPX $name not found from ${File(".").absolutePath}")
    }

    fun read(name: String): List<TracePoint> = trkpt.findAll(file(name).readText()).map { m ->
        val body = m.groupValues[3]
        TracePoint(
            timeMs = Instant.parse(time.find(body)!!.groupValues[1]).toEpochMilli(),
            lat = m.groupValues[1].toDouble(),
            lon = m.groupValues[2].toDouble(),
            speedMps = speed.find(body)?.groupValues?.get(1)?.toDouble(),
            bearingDeg = null,
            accuracyM = 5.0,
        )
    }.toList()
}
