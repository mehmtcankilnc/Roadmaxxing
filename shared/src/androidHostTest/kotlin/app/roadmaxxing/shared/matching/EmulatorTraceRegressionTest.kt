package app.roadmaxxing.shared.matching

import app.roadmaxxing.shared.geo.LocalProjection
import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.testdata.TestData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression fixture: main-roads.gpx replayed on the Pixel_8 emulator and recorded by the real Android
 * pipeline (test-data/traces/README.md). Bogus emulator bearings, ~18 % missing fixes, D400 interchange.
 */
class EmulatorTraceRegressionTest {
    private val trace: List<TracePoint> by lazy {
        TestData.file("test-data/traces/main-roads.emulator-pixel8.csv").readLines().drop(1).filter { it.isNotBlank() }.map { l ->
            val c = l.split(",")
            require(c.size == 6) { "unexpected columns: $l" }
            TracePoint(c[0].toLong(), c[1].toDouble(), c[2].toDouble(), c[3].toDoubleOrNull(), c[4].toDoubleOrNull(), c[5].toDoubleOrNull())
        }
    }

    @Test
    fun containsOnlyReplayedGpxPositions() {
        val gpx = TestData.readGpx("main-roads.gpx")
        val proj = LocalProjection(gpx[0].lat, gpx[0].lon)
        val gx = gpx.map { proj.x(it.lon) }
        val gy = gpx.map { proj.y(it.lat) }
        var worst = 0.0
        for (p in trace) {
            val px = proj.x(p.lon)
            val py = proj.y(p.lat)
            var best = Double.MAX_VALUE
            for (i in 0 until gpx.size - 1) {
                val dx = gx[i + 1] - gx[i]
                val dy = gy[i + 1] - gy[i]
                val l2 = dx * dx + dy * dy
                val t = if (l2 == 0.0) 0.0 else (((px - gx[i]) * dx + (py - gy[i]) * dy) / l2).coerceIn(0.0, 1.0)
                best = minOf(best, hypot(px - gx[i] - t * dx, py - gy[i] - t * dy))
            }
            worst = maxOf(worst, best)
        }
        println("Emulator trace: ${trace.size} points, max distance to main-roads.gpx track %.2f m".format(java.util.Locale.ROOT, worst))
        assertTrue(worst <= 5.0, "a point is $worst m off the replayed GPX track")
    }

    @Test
    fun matchesEveryExpectedSegment() {
        val pack = RegionPack(TestData.packSource("test-data/regions/adana"))
        val expected = Json.parseToJsonElement(TestData.file("test-data/gpx/main-roads.expected.json").readText()).jsonObject
        val truth = GpxAccuracyTest().truth(pack, expected, TestData.readGpx("main-roads.gpx"))
        for ((name, t) in listOf("recorded accuracy" to trace, "no accuracy" to trace.map { it.copy(accuracyM = null) })) {
            val r = RoadMatcher(pack).match(t)
            val found = r.driven.map { it.segmentId }.toSet()
            val hit = found.count { it in truth.required }
            val falses = found.filter { it !in truth.required && it !in truth.neutral }
            println("Emulator trace ($name): $hit/${truth.required.size} required, ${falses.size} false, fraction ${r.matchedPointFraction}")
            assertEquals(truth.required.size, hit, "$name: missed ${(truth.required.keys - found).map { "${it / 4096}:${it % 4096}" }}")
            assertEquals(emptyList(), falses.map { "${it / 4096}:${it % 4096}" }, "$name: false segments")
            assertEquals(205, truth.required.size)
        }
    }
}
