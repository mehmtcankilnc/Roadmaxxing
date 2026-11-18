package app.roadmaxxing.shared.privacy

import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.summary.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivacyClipperTest {
    private val lat0 = 37.0
    private val lon0 = 35.3
    private val mPerDegLat = 111_000.0

    /** A straight north-south line through the centre, from [fromM] to [toM] metres north of it, every 10 m. */
    private fun line(fromM: Int, toM: Int, step: Int = 10): List<GeoPoint> =
        (fromM..toM step step).map { GeoPoint(lat0 + it / mPerDegLat, lon0) }

    private fun zone(r: Int, id: Long = 1, dLatM: Double = 0.0) =
        PrivacyZone(id, ZoneKind.Home, null, lat0 + dLatM / mPerDegLat, lon0, r)

    private fun d(p: GeoPoint, z: PrivacyZone) = Geo.distanceM(p.lat, p.lon, z.lat, z.lon)

    private fun assertOutside(pieces: List<List<GeoPoint>>, zones: List<PrivacyZone>) {
        for (piece in pieces) for (p in piece) for (z in zones) {
            assertTrue(d(p, z) >= z.radiusM - 1.0, "point ${d(p, z)} m from zone centre, radius ${z.radiusM}")
        }
    }

    @Test
    fun noZonesReturnsLineUnchanged() {
        val l = line(-500, 500)
        assertEquals(listOf(l), PrivacyClipper.clip(l, emptyList()))
    }

    @Test
    fun crossingSplitsIntoTwoPiecesWithPointsOnTheBoundary() {
        val z = zone(300)
        val pieces = PrivacyClipper.clip(line(-1000, 1000), listOf(z))
        assertEquals(2, pieces.size)
        assertOutside(pieces, listOf(z))
        assertEquals(300.0, d(pieces[0].last(), z), 1.0)
        assertEquals(300.0, d(pieces[1].first(), z), 1.0)
        assertEquals(-1000.0, (pieces[0].first().lat - lat0) * mPerDegLat, 0.01)
        assertEquals(1000.0, (pieces[1].last().lat - lat0) * mPerDegLat, 0.01)
    }

    @Test
    fun startingInsideDropsTheLeadingPart() {
        val z = zone(300)
        val pieces = PrivacyClipper.clip(line(0, 1000), listOf(z))
        assertEquals(1, pieces.size)
        assertEquals(300.0, d(pieces[0].first(), z), 1.0)
        assertEquals(1000.0, (pieces[0].last().lat - lat0) * mPerDegLat, 0.01)
    }

    @Test
    fun endingInsideDropsTheTrailingPart() {
        val z = zone(300)
        val pieces = PrivacyClipper.clip(line(-1000, 0), listOf(z))
        assertEquals(1, pieces.size)
        assertEquals(300.0, d(pieces[0].last(), z), 1.0)
        assertOutside(pieces, listOf(z))
    }

    @Test
    fun longSegmentWithOnlyTwoVerticesIsCutAtTheCircle() {
        val z = zone(300)
        val pieces = PrivacyClipper.clip(listOf(GeoPoint(lat0 - 0.01, lon0), GeoPoint(lat0 + 0.01, lon0)), listOf(z))
        assertEquals(2, pieces.size)
        assertEquals(2, pieces[0].size)
        assertOutside(pieces, listOf(z))
    }

    @Test
    fun fullyInsideGivesNothing() {
        assertTrue(PrivacyClipper.clip(line(-100, 100), listOf(zone(300))).isEmpty())
    }

    @Test
    fun tangentLineIsKept() {
        // Line 300 m east of the centre, zone radius 300: touches the circle only.
        val dLon = 300.0 / (111_320.0 * kotlin.math.cos(Geo.toRad(lat0)))
        val l = (-500..500 step 10).map { GeoPoint(lat0 + it / mPerDegLat, lon0 + dLon * 1.001) }
        val pieces = PrivacyClipper.clip(l, listOf(zone(300)))
        assertEquals(1, pieces.size)
        assertEquals(l.size, pieces[0].size)
    }

    @Test
    fun lineFarAwayIsUntouched() {
        val l = line(5000, 6000)
        assertEquals(listOf(l), PrivacyClipper.clip(l, listOf(zone(300))))
    }

    @Test
    fun twoOverlappingZonesAreCutAsOne() {
        val a = zone(300, 1, dLatM = 0.0)
        val b = zone(300, 2, dLatM = 400.0) // overlaps a between 100 and 300 m
        val pieces = PrivacyClipper.clip(line(-1000, 1500), listOf(a, b))
        assertEquals(2, pieces.size)
        assertOutside(pieces, listOf(a, b))
        assertEquals(-300.0, (pieces[0].last().lat - lat0) * mPerDegLat, 1.5)
        assertEquals(700.0, (pieces[1].first().lat - lat0) * mPerDegLat, 1.5)
    }

    @Test
    fun twoSeparateZonesGiveThreePieces() {
        val a = zone(200, 1)
        val b = zone(200, 2, dLatM = 1000.0)
        val pieces = PrivacyClipper.clip(line(-800, 1800), listOf(a, b))
        assertEquals(3, pieces.size)
        assertOutside(pieces, listOf(a, b))
    }

    @Test
    fun degenerateInputs() {
        assertTrue(PrivacyClipper.clip(emptyList(), listOf(zone(300))).isEmpty())
        assertTrue(PrivacyClipper.clip(listOf(GeoPoint(lat0, lon0)), listOf(zone(300))).isEmpty())
        // Duplicate vertices must not crash.
        val p = GeoPoint(lat0 + 0.01, lon0)
        assertEquals(1, PrivacyClipper.clip(listOf(p, p, p), listOf(zone(300))).size)
    }
}
