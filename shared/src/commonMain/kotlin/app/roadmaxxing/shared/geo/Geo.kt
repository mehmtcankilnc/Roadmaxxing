package app.roadmaxxing.shared.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Small geodesy helpers (spherical; good to ~0.3 % which is far below GPS noise). */
object Geo {
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    fun toRad(deg: Double): Double = deg * PI / 180.0
    fun toDeg(rad: Double): Double = rad * 180.0 / PI

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = toRad(lat1)
        val p2 = toRad(lat2)
        val sdp = sin((p2 - p1) / 2)
        val sdl = sin(toRad(lon2 - lon1) / 2)
        val h = sdp * sdp + cos(p1) * cos(p2) * sdl * sdl
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from north in [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = toRad(lat1)
        val p2 = toRad(lat2)
        val dl = toRad(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalizeDeg(toDeg(atan2(y, x)))
    }

    fun normalizeDeg(d: Double): Double {
        var r = d % 360.0
        if (r < 0) r += 360.0
        return r
    }

    /** Absolute angular difference in degrees, [0, 180]. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = normalizeDeg(a - b)
        return if (d > 180.0) 360.0 - d else d
    }

    /** XYZ tile column of a longitude at [zoom]. */
    fun tileX(lon: Double, zoom: Int): Int {
        val n = (1 shl zoom).toDouble()
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, (1 shl zoom) - 1)
    }

    /** XYZ tile row of a latitude at [zoom] (Web Mercator). */
    fun tileY(lat: Double, zoom: Int): Int {
        val n = (1 shl zoom).toDouble()
        val t = tan(toRad(lat.coerceIn(-85.05112878, 85.05112878)))
        val asinhTan = ln(t + sqrt(t * t + 1.0))
        return floor((1.0 - asinhTan / PI) / 2.0 * n).toInt().coerceIn(0, (1 shl zoom) - 1)
    }
}

/**
 * Local equirectangular projection to metres around a reference point.
 * Accurate to ~0.1 % within a few tens of km of the reference, enough for map matching.
 */
class LocalProjection(private val refLat: Double, private val refLon: Double) {
    private val mPerDegLat = 111_132.954 - 559.822 * cos(2 * Geo.toRad(refLat)) + 1.175 * cos(4 * Geo.toRad(refLat))
    private val mPerDegLon = 111_412.84 * cos(Geo.toRad(refLat)) - 93.5 * cos(3 * Geo.toRad(refLat))

    fun x(lon: Double): Double = (lon - refLon) * mPerDegLon
    fun y(lat: Double): Double = (lat - refLat) * mPerDegLat
}
