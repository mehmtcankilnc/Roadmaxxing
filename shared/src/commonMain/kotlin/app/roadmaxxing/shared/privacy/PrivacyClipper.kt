package app.roadmaxxing.shared.privacy

import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.geo.LocalProjection
import app.roadmaxxing.shared.summary.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Cuts the parts of a polyline that lie inside privacy zones. Pure geometry; each zone is handled in a local
 * equirectangular frame around its centre (exact enough for radii of a few hundred metres).
 */
object PrivacyClipper {

    private class Circle(zone: PrivacyZone) {
        val r = zone.radiusM.toDouble()
        val proj = LocalProjection(zone.lat, zone.lon)

        // Bounding box (degrees) a little larger than the circle, used only to skip far-away segments.
        private val padLat = r / 100_000.0 + 1e-6
        private val padLon = r / (100_000.0 * cos(Geo.toRad(zone.lat)).coerceAtLeast(0.05)) + 1e-6
        private val minLat = zone.lat - padLat
        private val maxLat = zone.lat + padLat
        private val minLon = zone.lon - padLon
        private val maxLon = zone.lon + padLon

        fun overlaps(a: GeoPoint, b: GeoPoint): Boolean =
            !(maxOf(a.lat, b.lat) < minLat || minOf(a.lat, b.lat) > maxLat ||
                maxOf(a.lon, b.lon) < minLon || minOf(a.lon, b.lon) > maxLon)

        fun inside(lat: Double, lon: Double): Boolean {
            val x = proj.x(lon)
            val y = proj.y(lat)
            return x * x + y * y < r * r
        }
    }

    /**
     * Returns the pieces of [line] outside every zone, in order. Cut points are interpolated exactly on the circle
     * boundary. Pieces with fewer than two points are dropped. Without zones the line is returned as one piece.
     */
    fun clip(line: List<GeoPoint>, zones: List<PrivacyZone>): List<List<GeoPoint>> {
        if (line.size < 2) return emptyList()
        if (zones.isEmpty()) return listOf(line)
        val circles = zones.map { Circle(it) }
        val out = ArrayList<List<GeoPoint>>()
        var cur = ArrayList<GeoPoint>()
        fun flush() {
            if (cur.size >= 2) out.add(cur)
            cur = ArrayList()
        }
        val ts = ArrayList<Double>()
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            ts.clear()
            ts.add(0.0)
            ts.add(1.0)
            var near = false
            for (c in circles) {
                if (!c.overlaps(a, b)) continue
                near = true
                addCrossings(c, a, b, ts)
            }
            if (!near) {
                if (cur.isEmpty()) cur.add(a)
                cur.add(b)
                continue
            }
            ts.sort()
            for (k in 0 until ts.size - 1) {
                val t0 = ts[k]
                val t1 = ts[k + 1]
                if (t1 - t0 < 1e-12) continue
                val mid = lerp(a, b, (t0 + t1) / 2)
                if (circles.any { it.inside(mid.lat, mid.lon) }) {
                    flush()
                } else {
                    if (cur.isEmpty()) cur.add(if (t0 == 0.0) a else lerp(a, b, t0))
                    cur.add(if (t1 == 1.0) b else lerp(a, b, t1))
                }
            }
        }
        flush()
        return out
    }

    private fun addCrossings(c: Circle, a: GeoPoint, b: GeoPoint, ts: MutableList<Double>) {
        val ax = c.proj.x(a.lon)
        val ay = c.proj.y(a.lat)
        val dx = c.proj.x(b.lon) - ax
        val dy = c.proj.y(b.lat) - ay
        val qa = dx * dx + dy * dy
        if (qa == 0.0) return
        val qb = 2 * (ax * dx + ay * dy)
        val qc = ax * ax + ay * ay - c.r * c.r
        val disc = qb * qb - 4 * qa * qc
        if (disc <= 0.0) return // no crossing, or tangent (touches only)
        val root = sqrt(disc)
        for (t in doubleArrayOf((-qb - root) / (2 * qa), (-qb + root) / (2 * qa))) {
            if (t > 0.0 && t < 1.0) ts.add(t)
        }
    }

    private fun lerp(a: GeoPoint, b: GeoPoint, t: Double) =
        GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}
