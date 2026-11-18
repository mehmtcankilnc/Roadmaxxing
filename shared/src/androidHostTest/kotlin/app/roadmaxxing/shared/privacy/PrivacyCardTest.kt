package app.roadmaxxing.shared.privacy

import app.roadmaxxing.shared.domain.DriveProcessor
import app.roadmaxxing.shared.domain.InMemoryDriveStore
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.domain.TraceMatcher
import app.roadmaxxing.shared.drive.DriveRecorder
import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.matching.RoadMatcher
import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.summary.GeoPoint
import app.roadmaxxing.shared.summary.SummaryRepository
import app.roadmaxxing.shared.testdata.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Privacy zones are cut out of the drive card and the city card; stored data and percentages do not change. */
class PrivacyCardTest {
    private val source = TestData.packSource("test-data/regions/adana")
    private val pack = RegionPack(source)

    private val store = InMemoryDriveStore()

    private fun replay(name: String): Long {
        val processor = DriveProcessor(
            store = store,
            matcher = { TraceMatcher { RoadMatcher(pack).match(it) } },
            ioDispatcher = Dispatchers.Unconfined,
            cpuDispatcher = Dispatchers.Unconfined,
        )
        val recorder = DriveRecorder(store)
        val pts = TestData.readGpx(name)
        pts.forEach { recorder.onLocation(it) }
        for (s in 1..600) recorder.onTick(pts.last().timeMs + s * 1000L)
        runBlocking { processor.processPending() }
        return store.allDrives().last().id
    }

    private fun repo(zones: List<PrivacyZone>) = SummaryRepository(
        packProvider = RegionPackProvider(source), store = store, clock = { 0L },
        dispatcher = Dispatchers.Unconfined, boundarySource = source, privacyZones = { zones },
    )

    private fun inside(p: GeoPoint, z: PrivacyZone) = Geo.distanceM(p.lat, p.lon, z.lat, z.lon) < z.radiusM * 0.995

    @Test
    fun driveCardHasNoGeometryInsideAZoneAndStoredDataIsUntouched() {
        val id = replay("main-roads.gpx")
        val plain = assertNotNull(runBlocking { repo(emptyList()).driveCard(id, "en") })
        // A zone centred in the middle of the card's own route, so the route is certainly cut.
        val mid = plain.route.first { !it.fresh }.points.let { it[it.size / 2] }
        val zone = PrivacyZone(1, ZoneKind.Home, "h", mid.lat, mid.lon, 500)
        val before = store.drive(id)

        val card = assertNotNull(runBlocking { repo(listOf(zone)).driveCard(id, "en") })
        assertTrue(card.route.isNotEmpty())
        for (line in card.route) for (p in line.points) assertTrue(!inside(p, zone), "point inside zone")
        assertTrue(card.route.sumOf { it.points.size } < plain.route.sumOf { it.points.size } + card.route.size * 2)
        // Something was actually removed: total length shrinks by roughly the chord (~1 km).
        val lenPlain = plain.route.filter { !it.fresh }.sumOf { lineLen(it.points) }
        val lenCut = card.route.filter { !it.fresh }.sumOf { lineLen(it.points) }
        assertTrue(lenPlain - lenCut > 500.0, "cut ${lenPlain - lenCut} m")
        // Numbers on the card and the stored drive are identical with and without zones.
        assertEquals(plain.distanceM, card.distanceM)
        assertEquals(plain.newLengthM, card.newLengthM)
        assertEquals(plain.avgSpeedMps, card.avgSpeedMps)
        assertEquals(before, store.drive(id))
    }

    @Test
    fun cityCardIsClippedButPercentagesAreNot() {
        replay("main-roads.gpx")
        val plain = assertNotNull(runBlocking { repo(emptyList()).cityCard("en") })
        val seed = plain.driven.first().let { it[it.size / 2] }
        val zone = PrivacyZone(1, ZoneKind.Work, null, seed.lat, seed.lon, 1000)

        val t0 = System.nanoTime()
        val card = assertNotNull(runBlocking { repo(listOf(zone)).cityCard("en") })
        val ms = (System.nanoTime() - t0) / 1_000_000
        for (line in card.driven) for (p in line.points()) assertTrue(!inside(p, zone))
        assertTrue(card.driven.sumOf { it.size } < plain.driven.sumOf { it.size })
        assertEquals(plain.percent, card.percent)
        assertEquals(plain.percentMajor, card.percentMajor)
        assertEquals(plain.percentLocal, card.percentLocal)
        assertEquals(plain.weekNewLengthM, card.weekNewLengthM)
        assertEquals(plain.districtsCompleted, card.districtsCompleted)
        println("city card with zone: $ms ms")
    }

    private fun List<GeoPoint>.points() = this

    private fun lineLen(pts: List<GeoPoint>): Double {
        var s = 0.0
        for (i in 1 until pts.size) s += Geo.distanceM(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        return s
    }
}
