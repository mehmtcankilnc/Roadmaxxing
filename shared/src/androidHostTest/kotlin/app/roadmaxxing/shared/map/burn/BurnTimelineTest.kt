package app.roadmaxxing.shared.map.burn

import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.domain.InMemoryDriveStore
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.matching.RoadMatcher
import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.onboarding.AppMetaStore
import app.roadmaxxing.shared.testdata.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BurnTimelineTest {
    private class MapStore(val map: MutableMap<String, String> = HashMap()) : AppMetaStore {
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    private class Fixture(val repo: BurnRepository, val driveId: Long, val trace: List<TracePoint>, val meta: MapStore)

    private val packProvider by lazy { RegionPackProvider(TestData.packSource("test-data/regions/adana")) }

    private fun fixture(gpx: String, meta: MapStore = MapStore()): Fixture {
        val trace = TestData.readGpx(gpx)
        val driven = RoadMatcher(packProvider.pack).match(trace).driven.map { it.segmentId }.toSet()
        val store = InMemoryDriveStore()
        val id = store.createDrive(trace.first().timeMs, "test")
        store.appendPoints(id, trace)
        store.completeProcessing(id, driven, trace.first().timeMs) {
            DriveSummary(DriveVerdict.Drive, 1.0, 1, trace.size, 1.0, driven.size, 0, 1, 1)
        }
        return Fixture(BurnRepository(store, packProvider, meta, Dispatchers.Default), id, trace, meta)
    }

    // ---- GeoJSON helpers -------------------------------------------------------------------------------------

    private val coordPair = Regex("""\[(-?[0-9.]+),(-?[0-9.]+)]""")
    private val lineRegex = Regex("""\{"type":"Feature","properties":\{"kind":"burnt"[^}]*},"geometry":\{"type":"LineString","coordinates":\[(.*?)]}}""")

    /** Each burnt LineString as list of (lat, lon). */
    private fun lines(json: String): List<List<Pair<Double, Double>>> =
        lineRegex.findAll(json).map { m ->
            coordPair.findAll(m.groupValues[1]).map { it.groupValues[2].toDouble() to it.groupValues[1].toDouble() }.toList()
        }.toList()

    private fun burntLength(json: String): Double = lines(json).sumOf { l ->
        (1 until l.size).sumOf { Geo.distanceM(l[it - 1].first, l[it - 1].second, l[it].first, l[it].second) }
    }

    /** Head as (lat, lon) or null. */
    private fun head(json: String): Pair<Double, Double>? {
        val i = json.indexOf("\"kind\":\"head\"")
        if (i < 0) return null
        val m = coordPair.find(json, i) ?: return null
        return m.groupValues[2].toDouble() to m.groupValues[1].toDouble()
    }

    // ---- tests on real data ----------------------------------------------------------------------------------

    @Test
    fun frontFollowsTheTrace() = runBlocking {
        for (gpx in listOf("main-roads.gpx", "residential.gpx")) {
            val f = fixture(gpx)
            val tl = assertNotNull(f.repo.timelineFor(f.driveId), gpx)
            val cum = DoubleArray(f.trace.size)
            for (i in 1 until f.trace.size) {
                cum[i] = cum[i - 1] + Geo.distanceM(f.trace[i - 1].lat, f.trace[i - 1].lon, f.trace[i].lat, f.trace[i].lon)
            }
            for (frac in listOf(0.25f, 0.5f, 0.75f)) {
                val h = assertNotNull(head(tl.frame(frac)), "$gpx head at $frac")
                var best = 0
                var bestD = Double.MAX_VALUE
                for (i in f.trace.indices) {
                    val d = Geo.distanceM(h.first, h.second, f.trace[i].lat, f.trace[i].lon)
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
                val traceFrac = cum[best] / cum.last()
                println("BURN $gpx front@$frac -> trace fraction ${"%.3f".format(traceFrac)} (head ${bestD.toInt()} m from fix)")
                assertTrue(bestD < 60.0, "$gpx head far from trace: $bestD m")
                assertTrue(kotlin.math.abs(traceFrac - frac) < 0.2, "$gpx front@$frac sits at trace fraction $traceFrac")
            }
        }
    }

    @Test
    fun boundaryProgressValues() = runBlocking {
        val f = fixture("main-roads.gpx")
        val tl = assertNotNull(f.repo.timelineFor(f.driveId))
        val empty = tl.frame(0f)
        assertEquals(0, lines(empty).size)
        assertNull(head(empty))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", empty)

        val full = tl.frame(1f)
        assertNull(head(full))
        assertEquals(tl.segmentCount, lines(full).size)
        assertEquals(tl.totalLengthM, burntLength(full), tl.totalLengthM * 0.001)
        // Out-of-range / NaN progress is clamped.
        assertEquals(full, tl.frame(5f))
        assertEquals(empty, tl.frame(-1f))
        assertEquals(empty, tl.frame(Float.NaN))
        assertNotNull(tl.bounds)
        assertTrue(head(tl.frame(0.5f)) != null)
    }

    @Test
    fun burntLengthIsMonotonicAndProportional() = runBlocking {
        val f = fixture("residential.gpx")
        val tl = assertNotNull(f.repo.timelineFor(f.driveId))
        var prev = -1.0
        for (i in 0..100) {
            val len = burntLength(tl.frame(i / 100f))
            assertTrue(len >= prev - 0.01, "length dropped at step $i: $prev -> $len")
            prev = len
        }
        val half = burntLength(tl.frame(0.5f))
        assertEquals(tl.totalLengthM * 0.5, half, tl.totalLengthM * 0.01)
    }

    @Test
    fun durationIsClampedAndScaled() {
        assertEquals(600L, burnDurationMs(0.0))
        assertEquals(600L, burnDurationMs(Double.NaN))
        assertEquals(600L, burnDurationMs(-5.0))
        assertEquals(901L, burnDurationMs(1_000.0)) // 600 + 1400 * 0.1^(2/3)
        assertEquals(1481L, burnDurationMs(5_000.0)) // 600 + 1400 * 0.5^(2/3)
        assertEquals(2000L, burnDurationMs(10_000.0))
        assertEquals(2000L, burnDurationMs(50_000.0))
        runBlocking {
            for (gpx in listOf("main-roads.gpx", "residential.gpx", "intercity.gpx")) {
                val tl = fixture(gpx).let { it.repo.timelineFor(it.driveId) } ?: continue
                println("BURN $gpx: ${tl.segmentCount} segments, ${tl.totalLengthM.toInt()} m, ${tl.durationMs} ms")
                assertTrue(tl.durationMs in 600L..2000L)
            }
        }
    }

    @Test
    fun shownFlagRoundTrip() = runBlocking {
        val f = fixture("residential.gpx")
        assertNotNull(f.repo.pending())
        assertEquals(f.driveId, f.repo.pending()!!.driveId)
        f.repo.markShown(f.driveId)
        assertEquals(f.driveId.toString(), f.meta.map["burn_shown_drive_id"])
        assertNull(f.repo.pending())
        // timelineFor ignores the flag.
        assertNotNull(f.repo.timelineFor(f.driveId))
        // The flag survives a new repository instance on the same storage.
        assertNull(fixture("residential.gpx", f.meta).repo.pending())
    }

    @Test
    fun driveWithoutFreshSegmentsHasNoTimeline() = runBlocking {
        val trace = TestData.readGpx("residential.gpx")
        val store = InMemoryDriveStore()
        val id = store.createDrive(trace.first().timeMs, "test")
        store.appendPoints(id, trace)
        store.completeProcessing(id, emptySet(), trace.first().timeMs) {
            DriveSummary(DriveVerdict.Drive, 1.0, 1, trace.size, 1.0, 0, 0, 1, 1)
        }
        val repo = BurnRepository(store, packProvider, MapStore(), Dispatchers.Default)
        assertNull(repo.pending())
        assertNull(repo.timelineFor(id))
    }

    // ---- synthetic ordering / perf ---------------------------------------------------------------------------

    private fun road(id: Long, vararg lons: Double) =
        Triple(id, DoubleArray(lons.size) { 37.0 }, lons)

    private val trackLons = DoubleArray(101) { 35.0 + it * 0.0001 }
    private val trackLats = DoubleArray(101) { 37.0 }

    @Test
    fun reversedAndTiedRoadsAreOrderedDeterministically() {
        val roads = listOf(
            road(30, 35.0070, 35.0050), // driven east->west relative to its stored vertices: reversed
            road(20, 35.0020, 35.0040),
            road(11, 35.0080, 35.0090), // two roads with identical fix indices tie on id
            road(10, 35.0080, 35.0090),
        )
        val tl = BurnTimeline.build(1, roads, trackLats, trackLons)
        // 30 lies from index 70 to 50, so its key is (50, 70): it sorts after 20 (20, 40) and before 10/11 (80, 90).
        assertEquals(listOf(20L, 30L, 10L, 11L), tl.segmentIds)
        val (_, lons30) = tl.segmentVertices(1)
        assertEquals(35.0050, lons30.first(), 1e-9, "road 30 must be reversed to follow the trace")
        assertEquals(35.0070, lons30.last(), 1e-9)
        // Deterministic regardless of input order.
        assertEquals(tl.segmentIds, BurnTimeline.build(1, roads.reversed(), trackLats, trackLons).segmentIds)
    }

    @Test
    fun frameIsClippedMidSegment() {
        val tl = BurnTimeline.build(1, listOf(road(1, 35.0, 35.001, 35.002)), trackLats, trackLons)
        val json = tl.frame(0.25f)
        val l = lines(json).single()
        val h = assertNotNull(head(json))
        assertEquals(l.last().second, h.second, 1e-6)
        assertEquals(35.0005, h.second, 1e-5)
        assertEquals(tl.totalLengthM * 0.25, burntLength(json), 0.5)
        assertFalse(json.contains("35.002"))
    }

    @Test
    fun frameIsCheapFor500Segments() {
        val roads = (0 until 500).map { i ->
            val lon0 = 35.0 + i * 0.0002
            Triple(i.toLong(), doubleArrayOf(37.0, 37.0001, 37.0002, 37.0003), doubleArrayOf(lon0, lon0 + 0.00005, lon0 + 0.0001, lon0 + 0.00015))
        }
        val fixLons = DoubleArray(501) { 35.0 + it * 0.0002 }
        val tl = BurnTimeline.build(1, roads, DoubleArray(501) { 37.0 }, fixLons)
        repeat(200) { tl.frame(it / 200f) } // warm-up
        var worst = 0.0
        var sum = 0.0
        val n = 300
        for (i in 0 until n) {
            val t = System.nanoTime()
            tl.frame((i + 1) / (n + 1f))
            val ms = (System.nanoTime() - t) / 1e6
            sum += ms
            if (ms > worst) worst = ms
        }
        println("PERF burn frame (500 segments): avg=${"%.3f".format(sum / n)} ms worst=${"%.3f".format(worst)} ms")
        assertTrue(sum / n < 2.0, "avg frame ${sum / n} ms")
    }
}
