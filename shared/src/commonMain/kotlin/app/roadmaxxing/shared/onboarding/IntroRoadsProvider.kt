package app.roadmaxxing.shared.onboarding

import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.geo.LatLon
import app.roadmaxxing.shared.geo.LocalProjection
import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.region.Segment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * A light sample of roads for the onboarding intro animation. [south], [west], [north], [east] is the
 * fixed city-core box (degrees) the roads were clipped to, not the extent of the data, so a renderer can
 * fill its frame with it.
 */
data class IntroRoads(
    val roads: List<List<LatLon>>,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

/**
 * Picks the roads of a fixed city-core box from the embedded region pack: every segment of the chosen
 * classes that intersects the box, consecutive pieces of one way merged into a single polyline, clipped to
 * the box and simplified. Only the tiles under the box are decoded (never the whole pack). The result is
 * cached in memory after the first [load].
 */
class IntroRoadsProvider(
    private val packProvider: RegionPackProvider,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()
    private var cached: IntroRoads? = null

    suspend fun load(): IntroRoads {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: withContext(dispatcher) { build(packProvider.pack) }.also { cached = it }
        }
    }

    /** One merged way, clipped to the box. */
    private class Way(val roadClass: Int, val runs: List<List<LatLon>>, val lengthDm: Long)

    internal fun build(pack: RegionPack): IntroRoads {
        val box = boxFor(pack)
        val segments = pack.segmentsInBox(box.south, box.west, box.north, box.east)
            .filter { it.roadClass <= MAX_CLASS && it.vertexCount >= 2 }
        val ways = mergeWays(segments, box)
        val proj = LocalProjection((box.south + box.north) / 2, (box.west + box.east) / 2)

        // Major roads (classes 0..9) first, raising the tolerance until they fit their share of the budget.
        val major = ways.filter { it.roadClass < FIRST_LOCAL_CLASS }
        var tolerance = START_TOLERANCE_M
        var majorRoads: List<List<LatLon>>
        while (true) {
            majorRoads = simplifyAll(major, proj, tolerance)
            if (majorRoads.sumOf { it.size } <= MAX_MAJOR_POINTS || tolerance >= MAX_TOLERANCE_M) break
            tolerance *= 2
        }
        val roads = ArrayList(majorRoads)
        var points = roads.sumOf { it.size }

        // Residential only fills what is left, longest ways first.
        val local = ways.filter { it.roadClass >= FIRST_LOCAL_CLASS }
            .sortedWith(compareByDescending<Way> { it.lengthDm }.thenBy { it.runs.first().first().lat })
        for (way in local) {
            if (roads.size >= MAX_POLYLINES) break
            val simplified = simplifyAll(listOf(way), proj, tolerance)
            val cost = simplified.sumOf { it.size }
            if (points + cost > MAX_POINTS) continue
            roads.addAll(simplified)
            points += cost
        }
        return IntroRoads(roads, box.south, box.west, box.north, box.east)
    }

    private class Box(val south: Double, val west: Double, val north: Double, val east: Double)

    /** The fixed Adana core box when it lies in the pack, otherwise a same-sized box on the province centre. */
    private fun boxFor(pack: RegionPack): Box {
        val adana = Box(CORE_SOUTH, CORE_WEST, CORE_NORTH, CORE_EAST)
        val prov = pack.regionBbox(pack.provinceId) ?: return adana
        val cLat = (adana.south + adana.north) / 2
        val cLon = (adana.west + adana.east) / 2
        if (cLat in prov.south..prov.north && cLon in prov.west..prov.east) return adana
        val lat = (prov.south + prov.north) / 2
        val lon = (prov.west + prov.east) / 2
        val hLat = (adana.north - adana.south) / 2
        val hLon = (adana.east - adana.west) / 2
        return Box(lat - hLat, lon - hLon, lat + hLat, lon + hLon)
    }

    /** Joins consecutive ordinals of the same way, then clips each chain to the box. */
    private fun mergeWays(segments: List<Segment>, box: Box): List<Way> {
        val out = ArrayList<Way>()
        val byWay = segments.groupBy { it.wayId }
        for ((_, parts) in byWay) {
            val sorted = parts.sortedBy { it.ordinal }
            var chain = ArrayList<LatLon>()
            var chainLen = 0L
            var cls = sorted.first().roadClass
            var prevOrdinal = Int.MIN_VALUE
            fun flush() {
                if (chain.size >= 2) {
                    val runs = clip(chain, box)
                    if (runs.isNotEmpty()) out.add(Way(cls, runs, chainLen))
                }
                chain = ArrayList()
                chainLen = 0L
            }
            for (s in sorted) {
                if (s.ordinal != prevOrdinal + 1) flush()
                cls = minOf(cls, s.roadClass)
                val first = if (chain.isNotEmpty() && chain.last().lat == s.lats[0] && chain.last().lon == s.lons[0]) 1 else 0
                for (i in first until s.vertexCount) chain.add(LatLon(s.lats[i], s.lons[i]))
                chainLen += s.lengthDm
                prevOrdinal = s.ordinal
            }
            flush()
        }
        return out
    }

    /** Splits [pts] into the runs that lie inside [box], cutting edges at the box border (Liang-Barsky). */
    private fun clip(pts: List<LatLon>, box: Box): List<List<LatLon>> {
        val runs = ArrayList<List<LatLon>>()
        var cur = ArrayList<LatLon>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val dx = b.lon - a.lon
            val dy = b.lat - a.lat
            var t0 = 0.0
            var t1 = 1.0
            var visible = true
            val p = doubleArrayOf(-dx, dx, -dy, dy)
            val q = doubleArrayOf(a.lon - box.west, box.east - a.lon, a.lat - box.south, box.north - a.lat)
            for (k in 0 until 4) {
                if (p[k] == 0.0) {
                    if (q[k] < 0) visible = false
                } else {
                    val t = q[k] / p[k]
                    if (p[k] < 0) { if (t > t1) visible = false else if (t > t0) t0 = t }
                    else { if (t < t0) visible = false else if (t < t1) t1 = t }
                }
            }
            if (!visible) {
                if (cur.size >= 2) runs.add(cur)
                cur = ArrayList()
                continue
            }
            val start = if (t0 == 0.0) a else LatLon((a.lat + t0 * dy).coerceIn(box.south, box.north), (a.lon + t0 * dx).coerceIn(box.west, box.east))
            val end = if (t1 == 1.0) b else LatLon((a.lat + t1 * dy).coerceIn(box.south, box.north), (a.lon + t1 * dx).coerceIn(box.west, box.east))
            if (cur.isEmpty() || t0 != 0.0) {
                if (cur.size >= 2) runs.add(cur)
                cur = ArrayList()
                cur.add(start)
            }
            cur.add(end)
            if (t1 != 1.0) {
                if (cur.size >= 2) runs.add(cur)
                cur = ArrayList()
            }
        }
        if (cur.size >= 2) runs.add(cur)
        return runs
    }

    private fun simplifyAll(ways: List<Way>, proj: LocalProjection, toleranceM: Double): List<List<LatLon>> {
        val out = ArrayList<List<LatLon>>()
        for (w in ways) for (run in w.runs) {
            val s = simplify(run, proj, toleranceM)
            if (s.size >= 2) out.add(s)
        }
        return out
    }

    /** Douglas-Peucker with an explicit stack (no recursion depth issues). */
    private fun simplify(pts: List<LatLon>, proj: LocalProjection, toleranceM: Double): List<LatLon> {
        val n = pts.size
        val xs = DoubleArray(n) { proj.x(pts[it].lon) }
        val ys = DoubleArray(n) { proj.y(pts[it].lat) }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val range = stack.removeAt(stack.lastIndex)
            val a = range[0]
            val b = range[1]
            if (b <= a + 1) continue
            var maxD = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val d = perpDistance(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (maxD > toleranceM && idx >= 0) {
                keep[idx] = true
                stack.add(intArrayOf(a, idx))
                stack.add(intArrayOf(idx, b))
            }
        }
        val out = ArrayList<LatLon>()
        for (i in 0 until n) if (keep[i]) out.add(pts[i])
        return out
    }

    private fun perpDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    companion object {
        /** Fixed Adana city-core box (both banks of the Seyhan), degrees. */
        const val CORE_SOUTH: Double = 36.96
        const val CORE_NORTH: Double = 37.04
        const val CORE_WEST: Double = 35.26
        const val CORE_EAST: Double = 35.38

        /** Motorway .. tertiary_link are codes 0..9; residential (10) only fills the remaining budget. */
        const val MAX_CLASS: Int = 10
        private const val FIRST_LOCAL_CLASS = 10
        const val MAX_POLYLINES: Int = 3_000
        const val MAX_POINTS: Int = 4_800
        private const val MAX_MAJOR_POINTS = 3_600
        private const val START_TOLERANCE_M = 4.0
        private const val MAX_TOLERANCE_M = 200.0
    }
}
