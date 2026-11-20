package app.roadmaxxing.shared.sync

import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.summary.GeoPoint
import app.roadmaxxing.shared.testdata.TestData
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncCodecTest {

    private fun drive(route: List<GeoPoint>, segs: List<Long>) = RemoteDrive(
        syncId = "abc", startMs = 1_700_000_000_000, endMs = 1_700_003_600_000, verdict = "Drive",
        distanceM = 43_210.5, durationMs = 3_600_000, avgSpeedMps = 13.4, maxSpeedMps = 27.9, newLengthDm = 123_456,
        route = route, firstDrivenSegmentIds = segs,
    )

    private fun walk(n: Int, seed: Int = 1): List<GeoPoint> {
        val r = Random(seed)
        var lat = 37.0
        var lon = 35.3
        return List(n) {
            lat += (r.nextDouble() - 0.3) * 0.0004
            lon += (r.nextDouble() - 0.3) * 0.0004
            GeoPoint(Math.round(lat * 1e5) / 1e5, Math.round(lon * 1e5) / 1e5)
        }
    }

    private fun docBytes(m: Map<String, Any?>): Int = m.entries.sumOf { (k, v) ->
        k.length + 1 + when (v) {
            is String -> v.toByteArray().size + 1
            else -> 8
        }
    }

    @Test
    fun routeAndIdsRoundTrip() {
        val route = walk(300)
        val segs = listOf(5L, 3L, 4_100_000_000_000L, 12L, 3L)
        val doc = SyncCodec.encodeDrive(drive(route, segs))
        val back = assertNotNull(SyncCodec.decodeDrive("abc", doc))
        assertEquals(route.size, back.route.size)
        route.zip(back.route).forEach { (a, b) ->
            assertTrue(abs(a.lat - b.lat) < 1e-9 && abs(a.lon - b.lon) < 1e-9, "$a vs $b")
        }
        assertEquals(segs.toSortedSet().toList(), back.firstDrivenSegmentIds)
        assertEquals(13.4, back.avgSpeedMps)
        assertEquals("Drive", back.verdict)
        assertEquals(123_456L, back.newLengthDm)
    }

    @Test
    fun negativeCoordinatesAndRoundingSurvive() {
        val pts = listOf(GeoPoint(-33.865143, 151.209900), GeoPoint(-33.86521, -151.2))
        val back = SyncEncoding.decodeRoute(SyncEncoding.encodeRoute(pts))!!
        assertTrue(abs(back[0].lat - -33.86514) < 1e-9 && abs(back[0].lon - 151.2099) < 1e-9)
        assertTrue(abs(back[1].lon - -151.2) < 1e-9)
    }

    @Test
    fun emptyRouteAndNoSegmentsAndMissingOptionals() {
        val d = RemoteDrive("x", 1, null, null, null, null, null, null, null, emptyList(), emptyList())
        val doc = SyncCodec.encodeDrive(d)
        assertEquals(d, SyncCodec.decodeDrive("x", doc))
    }

    @Test
    fun firestoreNumberTypesAreAccepted() {
        val doc = SyncCodec.encodeDrive(drive(walk(5), listOf(1, 2))).toMutableMap()
        doc["v"] = 1
        doc["start"] = 1_700_000_000_000.0
        doc["dur"] = 3_600_000
        val back = assertNotNull(SyncCodec.decodeDrive("abc", doc))
        assertEquals(1_700_000_000_000, back.startMs)
        assertEquals(3_600_000L, back.durationMs)
    }

    @Test
    fun unknownOrInvalidDocumentsDecodeToNull() {
        val good = SyncCodec.encodeDrive(drive(walk(5), listOf(1)))
        assertNull(SyncCodec.decodeDrive("abc", good + ("v" to 2L)))
        assertNull(SyncCodec.decodeDrive("abc", good - "v"))
        assertNull(SyncCodec.decodeDrive("abc", good - "start"))
        assertNull(SyncCodec.decodeDrive("abc", good + ("route" to "\u0001bad")))
        assertNull(SyncCodec.decodeDrive("abc", good + ("segs" to "_")))   // dangling continuation
        assertNull(SyncCodec.decodeDrive("", good))
        assertNull(SyncCodec.decodeProfile(mapOf("v" to 9L, "updatedAt" to 1L)))
        assertNull(SyncCodec.decodeProfile(mapOf("v" to 1L)))
    }

    @Test
    fun fiveThousandSegmentsRoundTrip() {
        val r = Random(7)
        val ids = List(5000) { r.nextLong(0, 5_000_000_000_000L) }
        val back = SyncCodec.decodeDrive("abc", SyncCodec.encodeDrive(drive(walk(300), ids)))!!
        assertEquals(ids.toSortedSet().toList(), back.firstDrivenSegmentIds)
    }

    @Test
    fun hugeDocumentDropsRouteButNeverSegmentIds() {
        val ids = List(150_000) { it * 3_000_000L + 17 }
        val doc = SyncCodec.encodeDrive(drive(walk(300_000), ids))
        val size = docBytes(doc)
        assertTrue(size <= SyncCodec.MAX_DRIVE_DOC_BYTES, "size $size")
        val back = SyncCodec.decodeDrive("abc", doc)!!
        assertEquals(ids, back.firstDrivenSegmentIds)
        assertTrue(back.route.size < 300_000)
    }

    @Test
    fun profileRoundTrip() {
        val p = RemoteProfile(
            1234, mapOf("distance_unit" to "mi", "quiet_hours" to "1|1320|420"),
            listOf(RemoteZone("HOME", "Home", 37.001, 35.302, 300), RemoteZone("CUSTOM", null, -1.5, 2.5, 200)),
        )
        assertEquals(p, SyncCodec.decodeProfile(SyncCodec.encodeProfile(p)))
        val empty = RemoteProfile(0, emptyMap(), emptyList())
        assertEquals(empty, SyncCodec.decodeProfile(SyncCodec.encodeProfile(empty)))
    }

    /** Measures encodings with real Adana segment ids (worst case: ids drawn uniformly over the whole pack). */
    @Test
    fun measureEncodingSizes() {
        val pack = RegionPack(TestData.packSource("test-data/regions/adana"))
        val all = pack.allSegmentIds().sorted()
        val r = Random(3)
        val report = StringBuilder("segments in pack: ${all.size}\n")
        for (n in listOf(300, 1500, 5000)) {
            val uniform = List(n) { all[r.nextInt(all.size)] }.toSortedSet().toList()
            val start = r.nextInt(all.size - n)
            val local = all.subList(start, start + n)   // id-contiguous run: best case
            for ((label, ids) in listOf("uniform" to uniform, "contiguous" to local)) {
                val varint = SyncEncoding.encodeIds(ids).length
                var prev = 0L
                val base36 = ids.sumOf { id -> (id - prev).toString(36).length.also { prev = id } + 1 }
                report.appendLine("$n $label: varint=$varint B, base36+comma=$base36 B, number list=${ids.size * 8} B")
            }
        }
        val route300 = SyncEncoding.encodeRoute(walk(300)).length
        val doc1h = docBytes(SyncCodec.encodeDrive(drive(walk(300), List(1500) { all[r.nextInt(all.size)] })))
        val doc5k = docBytes(SyncCodec.encodeDrive(drive(walk(300), List(5000) { all[r.nextInt(all.size)] })))
        report.appendLine("route 300 pts polyline=$route300 B (number list ${300 * 16} B)")
        report.appendLine("drive doc: 1500 segs uniform=$doc1h B, 5000 segs uniform=$doc5k B")
        println(report)
        java.io.File(System.getProperty("java.io.tmpdir"), "sync-encoding-sizes.txt").writeText(report.toString())
        assertTrue(doc1h < 15_000, "1h doc is $doc1h B")
    }
}
