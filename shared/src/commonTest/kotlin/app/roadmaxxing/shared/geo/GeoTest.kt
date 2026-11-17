package app.roadmaxxing.shared.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class GeoTest {
    @Test
    fun distanceMatchesFixtureLengths() {
        // FORMAT.md fixture: 0.004 deg lon at 37.002 N ~ 355.8 m, 0.003 deg lat ~ 332.9 m (ellipsoidal).
        assertEquals(355.8, Geo.distanceM(37.002, 35.002, 37.002, 35.006), 1.5)
        assertEquals(332.9, Geo.distanceM(37.002, 35.002, 37.005, 35.002), 1.5)
        assertEquals(0.0, Geo.distanceM(37.0, 35.0, 37.0, 35.0))
    }

    @Test
    fun bearings() {
        assertEquals(0.0, Geo.bearingDeg(37.0, 35.0, 37.1, 35.0), 1e-6)
        assertEquals(90.0, Geo.bearingDeg(37.0, 35.0, 37.0, 35.1), 0.1)
        assertEquals(180.0, Geo.bearingDeg(37.1, 35.0, 37.0, 35.0), 1e-6)
        assertEquals(270.0, Geo.bearingDeg(37.0, 35.1, 37.0, 35.0), 0.1)
        assertEquals(20.0, Geo.angleDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDiffDeg(0.0, 180.0), 1e-9)
        assertEquals(350.0, Geo.normalizeDeg(-10.0), 1e-9)
    }

    @Test
    fun slippyTiles() {
        assertEquals(4892, Geo.tileX(35.01, 13))
        assertEquals(3188, Geo.tileY(37.005, 13))
        // Adana manifest corner tile [4887, 3178]: lon 34.8, lat ~37.6
        assertEquals(4887, Geo.tileX(34.80, 13))
    }

    @Test
    fun localProjectionScale() {
        val p = LocalProjection(37.0, 35.0)
        assertEquals(355.8, p.x(35.004) - p.x(35.0), 0.5)
        assertEquals(332.9, p.y(37.003) - p.y(37.0), 0.5)
    }
}
