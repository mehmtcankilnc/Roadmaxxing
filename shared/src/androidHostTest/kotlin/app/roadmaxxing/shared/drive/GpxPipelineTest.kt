package app.roadmaxxing.shared.drive

import app.roadmaxxing.shared.domain.DriveProcessor
import app.roadmaxxing.shared.domain.DriveStatus
import app.roadmaxxing.shared.domain.InMemoryDriveStore
import app.roadmaxxing.shared.domain.TraceMatcher
import app.roadmaxxing.shared.matching.RoadMatcher
import app.roadmaxxing.shared.progress.ProgressCalculator
import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.region.RegionPackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end on the JVM: GPX track -> recorder (detector + store) -> processor (B1's matcher on the real Adana
 * pack) -> driven segments -> progress. Mirrors what the Android service does, without the emulator.
 */
class GpxPipelineTest {

    private val pack: RegionPack by lazy {
        val dir = listOf(File("../test-data/regions/adana"), File("test-data/regions/adana")).first { it.exists() }
        RegionPack(RegionPackSource { path -> File(dir, path).takeIf { it.exists() }?.readBytes() })
    }

    private class Pipeline(pack: RegionPack) {
        val store = InMemoryDriveStore()
        val processor = DriveProcessor(
            store = store,
            matcher = { TraceMatcher { RoadMatcher(pack).match(it) } },
            ioDispatcher = Dispatchers.Unconfined,
            cpuDispatcher = Dispatchers.Unconfined,
        )
        private var offset = 0L

        /** Replays a track like the emulator (no activity signal), then 10 min standing still. */
        fun replay(name: String) {
            val recorder = DriveRecorder(store)
            val pts = GpxReader.read(name).map { it.copy(timeMs = it.timeMs + offset) }
            pts.forEach { recorder.onLocation(it) }
            val last = pts.last().timeMs
            for (s in 1..600) recorder.onTick(last + s * 1000L)
            offset += 86_400_000L // next replay a day later, so drives never overlap
            runBlocking { processor.processPending() }
        }
    }

    private fun adanaPercent(store: InMemoryDriveStore): Double {
        val province = pack.regions.first { it.level == 1 }
        return ProgressCalculator(pack).compute(store.drivenSegmentIds()).first { it.regionId == province.id }.percent
    }

    @Test
    fun scenariosProduceTheExpectedDrivesAndProgress() {
        val p = Pipeline(pack)
        val expected = listOf(
            "residential" to 1, "parallel-roads" to 1, "main-roads" to 1, "traffic-stop" to 1, "traffic-stop-long" to 2,
            "not-a-drive" to 0, "not-a-drive-train" to 0,
        )
        val report = StringBuilder()
        for ((name, drives) in expected) {
            val before = p.store.allDrives().size
            val pctBefore = adanaPercent(p.store)
            p.replay(name)
            val new = p.store.allDrives().drop(before)
            assertTrue(new.all { it.status == DriveStatus.Processed }, "$name: unprocessed drives")
            val accepted = new.filter { it.summary?.verdict == DriveVerdict.Drive }
            val pctAfter = adanaPercent(p.store)
            report.appendLine(
                "$name: recordings=${new.size} drives=${accepted.size} " +
                    new.joinToString { "[${it.summary?.verdict} frac=${"%.2f".format(it.summary?.matchedPointFraction)} " +
                        "km=${"%.2f".format((it.summary?.distanceM ?: 0.0) / 1000)} newKm=${"%.2f".format((it.summary?.newLengthDm ?: 0) / 10_000.0)} " +
                        "proc=${it.summary?.processingMs}ms]" } +
                    " adana%=${"%.4f".format(pctAfter)}",
            )
            assertEquals(drives, accepted.size, "$name\n$report")
            if (drives == 0) assertEquals(pctBefore, pctAfter, "$name changed the percentage")
        }
        // Same route again: nothing new.
        val pct = adanaPercent(p.store)
        p.replay("residential")
        val again = p.store.allDrives().last()
        assertEquals(DriveVerdict.Drive, again.summary?.verdict)
        assertEquals(0L, again.summary?.newLengthDm)
        assertEquals(pct, adanaPercent(p.store))
        println(report)
        File("build/gpx-pipeline-report.txt").writeText(report.toString())
    }
}
