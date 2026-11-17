package app.roadmaxxing.shared.map

import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.domain.InMemoryDriveStore
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.region.FixturePack
import app.roadmaxxing.shared.region.RoadLayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DrivenRoadsProviderTest {
    private val source = FixturePack.source()
    private val packProvider = RegionPackProvider(source)
    private val allIds: List<Long> get() = packProvider.pack.allSegmentIds().toList()

    private fun provider(store: InMemoryDriveStore, dispatcher: CoroutineDispatcher) =
        DrivenRoadsProvider(packProvider, store, dispatcher)

    private fun summary(at: Long, verdict: DriveVerdict) =
        DriveSummary(verdict, 100.0, 1000, 10, 1.0, 0, 0, 1, at)

    private fun drive(
        store: InMemoryDriveStore,
        ids: Collection<Long>,
        at: Long,
        verdict: DriveVerdict = DriveVerdict.Drive,
    ): Long {
        val id = store.createDrive(at, "test")
        store.completeProcessing(id, ids, at) { summary(at, verdict) }
        return id
    }

    @Test
    fun emptyStateHasNoBoundsAndValidEmptyCollection() = runTest {
        val p = provider(InMemoryDriveStore(), UnconfinedTestDispatcher(testScheduler))
        val snap = p.observe().first()
        assertEquals(0, snap.segmentCount)
        assertEquals(0, snap.freshSegmentCount)
        assertNull(snap.drivenBounds)
        val root = Json.parseToJsonElement(snap.geoJson).jsonObject
        assertEquals("FeatureCollection", root["type"]!!.jsonPrimitive.content)
        assertEquals(0, root["features"]!!.jsonArray.size)
    }

    @Test
    fun geoJsonIsValidAndCoordinatesMatchThePackExactly() = runTest {
        val store = InMemoryDriveStore()
        val ids = allIds
        drive(store, ids, 1_000)
        val snap = provider(store, UnconfinedTestDispatcher(testScheduler)).observe().first()
        assertEquals(ids.size, snap.segmentCount)
        val features = Json.parseToJsonElement(snap.geoJson).jsonObject["features"]!!.jsonArray
        assertEquals(ids.size, features.size)
        for (f in features) {
            val o = f.jsonObject
            val props = o["properties"]!!.jsonObject
            val id = props["id"]!!.jsonPrimitive.long
            val seg = assertNotNull(packProvider.pack.segment(id))
            assertEquals(id, o["id"]!!.jsonPrimitive.long)
            assertEquals(if (seg.layer == RoadLayer.Major) "major" else "local", props["layer"]!!.jsonPrimitive.content)
            val geom = o["geometry"]!!.jsonObject
            assertEquals("LineString", geom["type"]!!.jsonPrimitive.content)
            val coords: JsonArray = geom["coordinates"]!!.jsonArray
            assertEquals(seg.vertexCount, coords.size)
            for (i in 0 until seg.vertexCount) {
                assertEquals(seg.lons[i], coords[i].jsonArray[0].jsonPrimitive.double)
                assertEquals(seg.lats[i], coords[i].jsonArray[1].jsonPrimitive.double)
            }
        }
    }

    @Test
    fun freshIsOnlyTheLatestProcessedDrivesNewSegments() = runTest {
        val store = InMemoryDriveStore()
        val ids = allIds
        drive(store, ids.take(5), 1_000)
        drive(store, ids.take(8), 2_000)
        val snap = provider(store, UnconfinedTestDispatcher(testScheduler)).observe().first()
        assertEquals(8, snap.segmentCount)
        assertEquals(3, snap.freshSegmentCount)
        val fresh = Json.parseToJsonElement(snap.geoJson).jsonObject["features"]!!.jsonArray
            .filter { it.jsonObject["properties"]!!.jsonObject["fresh"]!!.jsonPrimitive.boolean }
            .map { it.jsonObject["properties"]!!.jsonObject["id"]!!.jsonPrimitive.long }
        assertEquals(ids.subList(5, 8).sorted(), fresh.sorted())
    }

    @Test
    fun nonDriveLatestProcessedMeansNoFresh() = runTest {
        val store = InMemoryDriveStore()
        drive(store, allIds.take(4), 1_000)
        drive(store, emptyList(), 2_000, DriveVerdict.TooShort)
        val snap = provider(store, UnconfinedTestDispatcher(testScheduler)).observe().first()
        assertEquals(4, snap.segmentCount)
        assertEquals(0, snap.freshSegmentCount)
    }

    @Test
    fun flowReEmitsWhenANewDriveIsProcessedAndReusesCache() = runTest {
        val store = InMemoryDriveStore()
        val ids = allIds
        drive(store, ids.take(3), 1_000)
        val p = provider(store, UnconfinedTestDispatcher(testScheduler))
        val seen = mutableListOf<DrivenRoadsSnapshot>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { p.observe().toList(seen) }
        assertEquals(3, seen.last().segmentCount)
        assertEquals(3, p.cachedSegmentCount)
        drive(store, ids.take(7), 2_000)
        val last = seen.last()
        assertEquals(7, last.segmentCount)
        assertEquals(4, last.freshSegmentCount)
        assertEquals(7, p.cachedSegmentCount)
        job.cancel()
    }

    @Test
    fun boundsMatchGeometryAndRegionsJson() = runTest {
        val store = InMemoryDriveStore()
        val ids = allIds
        drive(store, ids, 1_000)
        val p = provider(store, UnconfinedTestDispatcher(testScheduler))
        val snap = p.observe().first()
        var w = 1e9
        var s = 1e9
        var e = -1e9
        var n = -1e9
        for (id in ids) {
            val seg = packProvider.pack.segment(id)!!
            for (x in seg.lons) {
                if (x < w) w = x
                if (x > e) e = x
            }
            for (y in seg.lats) {
                if (y < s) s = y
                if (y > n) n = y
            }
        }
        assertEquals(GeoBounds(w, s, e, n), snap.drivenBounds)
        assertEquals(GeoBounds(35.0, 37.0, 35.02, 37.01), p.provinceBounds())
        assertEquals(GeoBounds(35.0, 37.0, 35.01, 37.01), p.regionBounds(11))
        assertEquals(GeoBounds(35.01, 37.0, 35.02, 37.01), p.regionBounds(12))
        assertNull(p.regionBounds(999))
    }

    @Test
    fun degreeFormatting() {
        fun f(v: Double) = StringBuilder().also { DrivenRoadsProvider.appendDegrees(it, v) }.toString()
        assertEquals("35", f(35.0))
        assertEquals("35.000001", f(35.000001))
        assertEquals("35.05", f(35.05))
        assertEquals("-0.5", f(-0.5))
        assertEquals("0", f(0.0))
        assertEquals("-122.419416", f(-122.419416))
    }
}
