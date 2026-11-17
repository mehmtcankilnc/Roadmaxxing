package app.roadmaxxing.shared.summary

import app.roadmaxxing.shared.domain.DriveProcessor
import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.domain.InMemoryDriveStore
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.domain.TraceMatcher
import app.roadmaxxing.shared.drive.DriveRecorder
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.matching.RoadMatcher
import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.region.FixturePack
import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.region.RegionPackSource
import app.roadmaxxing.shared.testdata.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryTest {

    private val adanaSource: RegionPackSource by lazy { TestData.packSource("test-data/regions/adana") }
    private val adana: RegionPack by lazy { RegionPack(adanaSource) }

    private fun expectedSpeeds(route: String): Pair<Double, Double> {
        val text = TestData.file("test-data/gpx/$route.expected.json").readText()
        val block = text.substringAfter("\"speed_kmh\"").substringBefore("}")
        fun num(key: String) = Regex("\"$key\":\\s*([0-9.]+)").find(block)!!.groupValues[1].toDouble()
        return num("avg_moving") / 3.6 to num("max") / 3.6
    }

    private val driveRoutes = listOf("main-roads", "residential", "parallel-roads", "traffic-stop", "traffic-stop-long", "intercity")

    private fun checkSpeeds(label: String, pts: List<TracePoint>, expAvg: Double, expMax: Double, avgTol: Double, maxTol: Double, report: StringBuilder) {
        val s = SpeedStats.compute(pts)
        val avg = assertNotNull(s.avgMps)
        val max = assertNotNull(s.maxMps)
        report.append("$label | %.1f | %.1f | %.1f | %.1f\n".format(avg * 3.6, expAvg * 3.6, max * 3.6, expMax * 3.6))
        assertTrue(avg <= max, "$label avg $avg > max $max")
        assertTrue(kotlin.math.abs(avg - expAvg) <= expAvg * avgTol, "$label avg $avg vs $expAvg")
        assertTrue(kotlin.math.abs(max - expMax) <= expMax * maxTol + 0.3, "$label max $max vs $expMax")
    }

    /** Deterministic position corruption: slowly drifting offset of up to [meters] m plus 20 % alternating jitter (the gpx `speed` field stays untouched). */
    private fun corrupt(pts: List<TracePoint>, meters: Double): List<TracePoint> {
        val dLat = meters / 111_000.0
        return pts.mapIndexed { i, p ->
            val phase = i / 20.0
            p.copy(
                lat = p.lat + dLat * kotlin.math.sin(phase * 1.7) + 0.2 * dLat * (if (i % 2 == 0) 1 else -1),
                lon = p.lon + dLat * kotlin.math.cos(phase * 1.3),
            )
        }
    }

    @Test
    fun speedsMatchExpectedJson() {
        val report = StringBuilder("route/variant | avg km/h | exp avg_moving | max km/h | exp max\n")
        for (route in driveRoutes) {
            val (expAvg, expMax) = expectedSpeeds(route)
            // Reported speed (gpxtpx:speed, +-0.3 m/s noise in the noisy files): 5 % for avg and max.
            checkSpeeds("$route clean reported", TestData.readGpx("$route.gpx"), expAvg, expMax, 0.05, 0.05, report)
            checkSpeeds("$route noisy reported", TestData.readGpx("$route.noisy.gpx"), expAvg, expMax, 0.05, 0.05, report)
            // Reported speed + 15 m corrupted positions: the reported speed carries the result.
            checkSpeeds("$route corrupted-positions reported", corrupt(TestData.readGpx("$route.gpx"), 15.0), expAvg, expMax, 0.05, 0.05, report)
            // No speed field: derived from positions (clean 5 %; noisy avg 12 %, max 20 % because 6 m drifting
            // position noise plus 1.5 m jitter on 1 s steps inflates the peak).
            checkSpeeds("$route clean derived", TestData.readGpx("$route.gpx", withSpeedAndCourse = false), expAvg, expMax, 0.05, 0.05, report)
            checkSpeeds("$route noisy derived", TestData.readGpx("$route.noisy.gpx", withSpeedAndCourse = false), expAvg, expMax, 0.12, 0.20, report)
        }
        File(TestData.repoRoot, "shared/build/summary-speed-report.txt").also { it.parentFile.mkdirs() }.writeText(report.toString())
    }

    @Test
    fun inconsistentReportedSpeedFallsBackToPositions() {
        val base = TestData.readGpx("main-roads.gpx")
        val derived = SpeedStats.compute(TestData.readGpx("main-roads.gpx", withSpeedAndCourse = false))
        // Reported speed is garbage (constant 60 m/s) while positions say ~12 m/s: positions win.
        val s = SpeedStats.compute(base.map { it.copy(speedMps = 60.0) })
        assertTrue(kotlin.math.abs(s.maxMps!! - derived.maxMps!!) <= 1.0, "max ${s.maxMps} vs ${derived.maxMps}")
        // Impossible reported values are ignored entirely.
        val t = SpeedStats.compute(base.map { it.copy(speedMps = 500.0) })
        assertTrue(kotlin.math.abs(t.maxMps!! - derived.maxMps!!) <= 1.0)
    }

    @Test
    fun spuriousReportedSpeedDoesNotChangeMax() {
        val pts = TestData.readGpx("main-roads.gpx")
        val base = SpeedStats.compute(pts)
        val bad = pts.toMutableList()
        bad[pts.size / 2] = bad[pts.size / 2].copy(speedMps = 69.0)
        assertTrue(kotlin.math.abs(SpeedStats.compute(bad).maxMps!! - base.maxMps!!) <= 1.0)
    }

    @Test
    fun singleSpuriousFixDoesNotChangeMax() {
        val pts = TestData.readGpx("main-roads.gpx")
        val base = SpeedStats.compute(pts)
        val bad = pts.toMutableList()
        val i = pts.size / 2
        bad[i] = bad[i].copy(lat = bad[i].lat + 0.005, lon = bad[i].lon + 0.005) // ~700 m jump for one fix
        val s = SpeedStats.compute(bad)
        assertTrue(kotlin.math.abs(s.maxMps!! - base.maxMps!!) <= 1.0, "max ${s.maxMps} vs ${base.maxMps}")
        assertTrue(s.avgMps!! <= s.maxMps!!)
    }

    @Test
    fun sparseTraceHasNoSpeeds() {
        assertEquals(SpeedStats(null, null), SpeedStats.compute(emptyList()))
        assertEquals(SpeedStats(null, null), SpeedStats.compute(TestData.readGpx("main-roads.gpx").take(4)))
    }

    private class Pipeline(pack: RegionPack) {
        val store = InMemoryDriveStore()
        val processor = DriveProcessor(
            store = store,
            matcher = { TraceMatcher { RoadMatcher(pack).match(it) } },
            ioDispatcher = Dispatchers.Unconfined,
            cpuDispatcher = Dispatchers.Unconfined,
        )
        var lastEnd = 0L

        /** Replays [name] shifted by [offsetMs], ends the drive, processes it and returns the new drive id. */
        fun replay(name: String, offsetMs: Long): Long {
            val recorder = DriveRecorder(store)
            val pts = TestData.readGpx(name).map { it.copy(timeMs = it.timeMs + offsetMs) }
            pts.forEach { recorder.onLocation(it) }
            val last = pts.last().timeMs
            for (s in 1..600) recorder.onTick(last + s * 1000L)
            runBlocking { processor.processPending() }
            lastEnd = last
            return store.allDrives().last().id
        }
    }

    private fun repo(store: InMemoryDriveStore, source: RegionPackSource, pack: RegionPack, now: Long = 0L) =
        SummaryRepository(
            packProvider = packProviderOf(pack, source), store = store, clock = { now },
            dispatcher = Dispatchers.Unconfined, boundarySource = source,
        )

    private fun packProviderOf(@Suppress("UNUSED_PARAMETER") pack: RegionPack, source: RegionPackSource) =
        RegionPackProvider(source)

    @Test
    fun driveCardPrivacyTrimFreshAndSizes() {
        val p = Pipeline(adana)
        val report = StringBuilder("route | route polylines | route points | fresh points | ms\n")
        for ((n, route) in listOf("residential", "main-roads", "intercity").withIndex()) {
            val id = p.replay("$route.gpx", n * 86_400_000L)
            val r = repo(p.store, adanaSource, adana)
            val t0 = System.nanoTime()
            val card = assertNotNull(runBlocking { r.driveCard(id, "tr") }, route)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val raw = p.store.allPoints(id)
            val first = raw.first()
            val last = raw.last()
            assertEquals("Adana", card.regionName)
            assertTrue(card.route.any { !it.fresh } && card.route.any { it.fresh }, "$route has trace and fresh lines")
            // Trimming is measured along the route; a loop that returns to its start is allowed to pass near it mid-route.
            if (route != "residential") for (line in card.route) for (pt in line.points) {
                assertTrue(Geo.distanceM(pt.lat, pt.lon, first.lat, first.lon) >= 290.0, "$route near start")
                assertTrue(Geo.distanceM(pt.lat, pt.lon, last.lat, last.lon) >= 290.0, "$route near end")
            }
            assertTrue(card.avgSpeedMps!! <= card.maxSpeedMps!!)
            assertEquals(card.newLengthM, p.store.drive(id)!!.summary!!.newLengthDm / 10.0)
            report.append("$route | ${card.route.size} | ${card.route.filter { !it.fresh }.sumOf { it.points.size }} | " +
                "${card.route.filter { it.fresh }.sumOf { it.points.size }} | $ms\n")
        }
        File(TestData.repoRoot, "shared/build/summary-card-report.txt").also { it.parentFile.mkdirs() }.writeText(report.toString())
    }

    @Test
    fun driveCardEmptyWhenShortAndNullWhenUnknownOrNotADrive() {
        val s = InMemoryDriveStore()
        val recorderId = s.createDrive(0, "t")
        val pts = TestData.readGpx("main-roads.gpx").take(25) // well under 600 m at city speed: shorter than 2 x 300 m trim
        s.appendPoints(recorderId, pts)
        s.markEnded(recorderId, pts.last().timeMs, "t", pts.first().timeMs, pts.last().timeMs)
        s.completeProcessing(recorderId, emptyList(), 0L) {
            DriveSummary(DriveVerdict.Drive, 500.0, 60_000, 60, 1.0, 0, 0, 0, 0)
        }
        val r = repo(s, adanaSource, adana)
        val card = assertNotNull(runBlocking { r.driveCard(recorderId, "en") })
        assertTrue(card.route.isEmpty(), "short drive has no route")
        assertNull(runBlocking { r.driveCard(999, "en") })
    }

    @Test
    fun cityCardEmptyState() {
        val c = assertNotNull(runBlocking { repo(InMemoryDriveStore(), adanaSource, adana).cityCard("en") })
        assertEquals(0.0, c.percent)
        assertEquals(0, c.districtsCompleted)
        assertTrue(c.districtsTotal > 0)
        assertEquals(0.0, c.weekNewLengthM)
        assertTrue(c.driven.isEmpty())
        assertTrue(c.boundary.isNotEmpty())
    }

    @Test
    fun cityCardAdanaAfterDrive() {
        val p = Pipeline(adana)
        p.replay("main-roads.gpx", 0L)
        val t0 = System.nanoTime()
        val c = assertNotNull(runBlocking { repo(p.store, adanaSource, adana, now = p.lastEnd + 1000).cityCard("tr") })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("Adana", c.name)
        assertTrue(c.percent > 0.0 && c.percent < 1.0)
        assertTrue(c.weekNewLengthM > 1000.0)
        assertTrue(c.driven.isNotEmpty())
        assertTrue(c.boundary.isNotEmpty())
        File(TestData.repoRoot, "shared/build/summary-city-report.txt").also { it.parentFile.mkdirs() }.writeText(
            "boundary rings=${c.boundary.size} points=${c.boundary.sumOf { it.size }}; driven lines=${c.driven.size} " +
                "points=${c.driven.sumOf { it.size }}; ms=$ms\n",
        )
    }

    private fun fixtureStore(): Triple<InMemoryDriveStore, RegionPack, RegionPackSource> {
        val source = FixturePack.source()
        return Triple(InMemoryDriveStore(), RegionPack(source), source)
    }

    private fun drive(store: InMemoryDriveStore, ids: Collection<Long>, firstDrivenMs: Long): Long {
        val d = store.createDrive(firstDrivenMs, "t")
        store.completeProcessing(d, ids, firstDrivenMs) { DriveSummary(DriveVerdict.Drive, 1.0, 1, 1, 1.0, ids.size, 0, 0, 0) }
        return d
    }

    @Test
    fun districtCountUsesCompletionThreshold() {
        val (store, pack, source) = fixtureStore()
        val east = pack.allSegmentIds().filter { pack.summary(it)!!.districtId == 12L }
        drive(store, east, 1000L)
        val c = assertNotNull(runBlocking { repo(store, source, pack, now = 2000L).cityCard("en") })
        assertEquals(2, c.districtsTotal)
        assertEquals(1, c.districtsCompleted)
        assertEquals("Testprovince", c.name)
        assertEquals("Testil", runBlocking { repo(store, source, pack).cityCard("tr") }!!.name)
        assertEquals(1, c.boundary.size)
        drive(store, pack.allSegmentIds().filter { it !in store.drivenSegmentIds() }, 1500L)
        val all = runBlocking { repo(store, source, pack, now = 2000L).cityCard("en") }!!
        assertEquals(2, all.districtsCompleted)
        assertEquals(100.0, all.percent, 1e-9)
    }

    @Test
    fun weeklyLengthCountsOnlySegmentsFirstDrivenInLastSevenDays() {
        val (store, pack, source) = fixtureStore()
        val day = 86_400_000L
        val now = 100 * day
        val ids = pack.allSegmentIds().toList()
        val old = ids.take(3)
        val recent = ids.drop(3).take(2)
        drive(store, old, now - 10 * day)
        drive(store, recent, now - day)
        val c = runBlocking { repo(store, source, pack, now = now).cityCard("en") }!!
        assertEquals(recent.sumOf { pack.summary(it)!!.lengthDm } / 10.0, c.weekNewLengthM, 1e-9)
    }

    @Test
    fun geometryHelpers() {
        val line = (0..100).map { GeoPoint(37.0 + it * 0.0001, 35.0) } // ~1.1 km straight
        assertEquals(2, CardGeometry.simplify(line, 5.0).size)
        val trimmed = CardGeometry.trimEnds(line, 300.0)
        assertEquals(CardGeometry.pathLengthM(line) - 600.0, CardGeometry.pathLengthM(trimmed), 1.0)
        assertTrue(CardGeometry.trimEnds(line.take(40), 300.0).isEmpty())
        val clipped = CardGeometry.clipByRoutePosition(line, line, 300.0)
        assertEquals(1, clipped.size)
        assertTrue(clipped[0].all { Geo.distanceM(it.lat, it.lon, line.first().lat, line.first().lon) >= 299.0 })
    }

    @Test
    fun cityCardColdTimeWithTileLookupVersusFullScan() {
        val p = Pipeline(adana)
        for ((n, r) in driveRoutes.withIndex()) p.replay("$r.gpx", n * 86_400_000L)
        val ids = p.store.drivenSegmentIds()
        // Before: the old implementation scanned every tile (fresh pack = cold cache).
        val coldOld = RegionPack(adanaSource)
        var old = 0
        val t0 = System.nanoTime()
        coldOld.forEachSegment { if (it.id in ids) old++ }
        val oldMs = (System.nanoTime() - t0) / 1_000_000
        // After: tiles around each first drive only.
        val coldNew = RegionPack(adanaSource)
        val t1 = System.nanoTime()
        val found = SegmentResolver(coldNew, p.store).resolve(ids, p.store.drivenSegmentFirstDrives(), scanForMissing = false)
        val newMs = (System.nanoTime() - t1) / 1_000_000
        assertEquals(old, found.size, "resolver finds every driven segment the full scan finds")
        assertTrue(coldNew.tileLoads < coldOld.tileLoads, "fewer tiles decoded")
        File(TestData.repoRoot, "shared/build/summary-city-perf.txt").writeText(
            "driven=${ids.size} fullScan: ${oldMs} ms, ${coldOld.tileLoads} tiles; tileLookup: ${newMs} ms, ${coldNew.tileLoads} tiles\n",
        )
    }

    @Test
    fun gridCellsRoundDownAlsoForNegativeCoordinates() {
        assertEquals(-1L, CardGeometry.cellIndex(-0.001, 0.002))
        assertEquals(0L, CardGeometry.cellIndex(0.001, 0.002))
        assertEquals(-2L, CardGeometry.cellIndex(-0.002001, 0.002))
        assertTrue(CardGeometry.cellKey(-1, -1) != CardGeometry.cellKey(0, 0))
    }

    @Test
    fun clipByRoutePositionWorksWithNegativeCoordinates() {
        // Route in the western/southern hemisphere (negative lon and lat), ~1.1 km straight.
        val line = (0..100).map { GeoPoint(-34.0 - it * 0.0001, -58.0) }
        val clipped = CardGeometry.clipByRoutePosition(line, line, 300.0)
        assertEquals(1, clipped.size)
        assertTrue(clipped[0].size in 44..49, "kept ${clipped[0].size}")
    }
}
