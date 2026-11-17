package app.roadmaxxing.shared.summary

import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.geo.LocalProjection
import kotlin.math.hypot

/** Pure geometry helpers for the share cards (no platform APIs). */
internal object CardGeometry {
    fun pathLengthM(points: List<GeoPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += Geo.distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        return sum
    }

    /** Douglas-Peucker with [toleranceM] metres, iterative (no recursion depth limit). Keeps both end points. */
    fun simplify(points: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
        if (points.size <= 2) return points
        val proj = LocalProjection(points[0].lat, points[0].lon)
        val xs = DoubleArray(points.size) { proj.x(points[it].lon) }
        val ys = DoubleArray(points.size) { proj.y(points[it].lat) }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val range = stack.removeAt(stack.size - 1)
            val a = range[0]
            val b = range[1]
            var worst = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = distToSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > worst) {
                    worst = d
                    idx = i
                }
            }
            if (idx >= 0 && worst > toleranceM) {
                keep[idx] = true
                stack.add(intArrayOf(a, idx))
                stack.add(intArrayOf(idx, b))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Removes the first and last [trimM] metres measured along the path. Empty when the path is shorter than
     * 2 x [trimM]. Cut points are interpolated, so the result starts and ends exactly [trimM] in.
     */
    fun trimEnds(points: List<GeoPoint>, trimM: Double): List<GeoPoint> {
        if (points.size < 2) return emptyList()
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cum[i] = cum[i - 1] + Geo.distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        val total = cum.last()
        if (total < 2 * trimM) return emptyList()
        val hi = total - trimM
        val out = ArrayList<GeoPoint>()
        out.add(interpolate(points, cum, trimM))
        for (i in points.indices) if (cum[i] > trimM && cum[i] < hi) out.add(points[i])
        out.add(interpolate(points, cum, hi))
        return out
    }

    private fun interpolate(points: List<GeoPoint>, cum: DoubleArray, at: Double): GeoPoint {
        var i = 1
        while (i < cum.size - 1 && cum[i] < at) i++
        val span = cum[i] - cum[i - 1]
        val t = if (span <= 0.0) 0.0 else ((at - cum[i - 1]) / span).coerceIn(0.0, 1.0)
        return GeoPoint(
            points[i - 1].lat + (points[i].lat - points[i - 1].lat) * t,
            points[i - 1].lon + (points[i].lon - points[i - 1].lon) * t,
        )
    }

    /**
     * Cuts [line] to the part whose nearest point on [route] lies between [trimM] and (route length - [trimM]) metres
     * along the route (same rule as [trimEnds]). Vertices with no route point within ~200 m are kept as they are.
     */
    fun clipByRoutePosition(line: List<GeoPoint>, route: List<GeoPoint>, trimM: Double): List<List<GeoPoint>> {
        if (route.size < 2) return emptyList()
        val cum = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cum[i] = cum[i - 1] + Geo.distanceM(route[i - 1].lat, route[i - 1].lon, route[i].lat, route[i].lon)
        }
        val total = cum.last()
        if (total < 2 * trimM) return emptyList()
        val cell = 0.002
        val grid = HashMap<Long, MutableList<Int>>()
        for (i in route.indices) {
            grid.getOrPut(cellKey(cellIndex(route[i].lon, cell), cellIndex(route[i].lat, cell))) { ArrayList() }.add(i)
        }
        val out = ArrayList<List<GeoPoint>>()
        var run = ArrayList<GeoPoint>()
        for (p in line) {
            val cx = cellIndex(p.lon, cell)
            val cy = cellIndex(p.lat, cell)
            var best = -1
            var bestD = Double.MAX_VALUE
            for (dx in -1L..1L) for (dy in -1L..1L) {
                for (i in grid[cellKey(cx + dx, cy + dy)] ?: continue) {
                    val d = Geo.distanceM(p.lat, p.lon, route[i].lat, route[i].lon)
                    if (d < bestD) {
                        bestD = d
                        best = i
                    }
                }
            }
            val keep = best < 0 || (cum[best] >= trimM && cum[best] <= total - trimM)
            if (keep) {
                run.add(p)
            } else {
                if (run.size >= 2) out.add(run)
                run = ArrayList()
            }
        }
        if (run.size >= 2) out.add(run)
        return out
    }

    /** Grid cell of a coordinate: rounds down (also for negative values, unlike `toLong()` which truncates to zero). */
    internal fun cellIndex(v: Double, cell: Double): Long = kotlin.math.floor(v / cell).toLong()

    internal fun cellKey(x: Long, y: Long): Long = (x shl 32) xor (y and 0xFFFFFFFFL)

    private fun distToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
