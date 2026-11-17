package app.roadmaxxing.shared.map

import app.roadmaxxing.shared.domain.InMemoryDriveStore
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.testdata.TestData
import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.matching.RoadMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrivenRoadsProviderPerfTest {
    private fun newProvider(): Pair<DrivenRoadsProvider, LongArray> {
        val source = TestData.packSource("test-data/regions/adana")
        val packProvider = RegionPackProvider(source)
        val all = packProvider.pack.allSegmentIds()
        return DrivenRoadsProvider(packProvider, InMemoryDriveStore(), Dispatchers.Default) to all
    }

    private fun packOf(p: DrivenRoadsProvider) = p.packForTest()

    private fun ms(block: () -> Unit): Long {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1_000_000
    }

    @Test
    fun fiveThousandRandomSegments() {
        val (p, all) = newProvider()
        val ids = all.toList().shuffled(Random(42)).take(5000).toLongArray().also { it.sort() }
        var snap: DrivenRoadsSnapshot? = null
        val locator = ms { packOf(p).segment(ids[0]) }
        val cold = ms { snap = p.render(ids, emptySet()) }
        println("PERF tile locator scan (first segment lookup): ${locator}ms")
        val warm = ms { snap = p.render(ids, emptySet()) }
        println("PERF 5000 segments: cold=${cold}ms warm=${warm}ms size=${snap!!.geoJson.length / 1024}KiB count=${snap!!.segmentCount}")
        assertEquals(5000, snap!!.segmentCount)
        assertTrue(warm < 200, "warm $warm ms")
    }

    @Test
    fun wholePackInformational() {
        val (p, all) = newProvider()
        var snap: DrivenRoadsSnapshot? = null
        val cold = ms { snap = p.render(all, emptySet()) }
        val warm = ms { snap = p.render(all, emptySet()) }
        println("PERF all ${all.size} segments: cold=${cold}ms warm=${warm}ms size=${snap!!.geoJson.length / 1024 / 1024}MiB count=${snap!!.segmentCount}")
        assertEquals(all.size, snap!!.segmentCount)
    }

    @Test
    fun drivenTraceRendersWithoutWholePackScan() {
        val source = TestData.packSource("test-data/regions/adana")
        val packProvider = RegionPackProvider(source)
        val pack = packProvider.pack
        val trace = TestData.readGpx("main-roads.gpx")
        val driven = RoadMatcher(pack).match(trace).driven.map { it.segmentId }.toSet()
        val store = InMemoryDriveStore()
        val id = store.createDrive(trace.first().timeMs, "test")
        store.appendPoints(id, trace)
        store.completeProcessing(id, driven, trace.first().timeMs) {
            DriveSummary(DriveVerdict.Drive, 1.0, 1, trace.size, 1.0, driven.size, 0, 1, 1)
        }
        val fresh = DrivenRoadsProvider(packProvider, store, Dispatchers.Default)
        val loadsBefore = pack.tileLoads
        var snap: DrivenRoadsSnapshot? = null
        val t = ms { snap = runBlocking { fresh.observe().first() } }
        val loads = pack.tileLoads - loadsBefore
        println("PERF trace-based render: ${t}ms, extra tile loads=$loads, segments=${snap!!.segmentCount}")
        assertEquals(driven.size, snap!!.segmentCount)
        assertTrue(loads < 100, "tile loads $loads suggests a whole-pack scan")
    }
}
