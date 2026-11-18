package app.roadmaxxing.shared.map.burn

import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.map.DrivenRoadsProvider
import app.roadmaxxing.shared.map.GeoBounds
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** One fresh road in drive order: vertices already oriented along the direction of travel. */
internal class BurnSegment(
    val id: Long,
    val lats: DoubleArray,
    val lons: DoubleArray,
) {
    val vertexCount: Int get() = lats.size

    /** cumDist[i] = metres from the first vertex to vertex i. */
    val cumDist = DoubleArray(lats.size).also { d ->
        for (i in 1 until lats.size) d[i] = d[i - 1] + Geo.distanceM(lats[i - 1], lons[i - 1], lats[i], lons[i])
    }
    val lengthM: Double get() = cumDist[cumDist.size - 1]

    /** Full LineString feature, rendered once. */
    val fullFeature: String by lazy {
        val sb = StringBuilder(vertexCount * 20 + 96)
        openFeature(sb, id)
        for (i in 0 until vertexCount) {
            if (i > 0) sb.append(',')
            appendCoord(sb, lons[i], lats[i])
        }
        sb.append("]}}")
        sb.toString()
    }
}

private fun openFeature(sb: StringBuilder, id: Long) {
    sb.append("{\"type\":\"Feature\",\"properties\":{\"kind\":\"burnt\",\"id\":").append(id)
        .append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
}

private fun appendCoord(sb: StringBuilder, lon: Double, lat: Double) {
    sb.append('[')
    DrivenRoadsProvider.appendDegrees(sb, lon)
    sb.append(',')
    DrivenRoadsProvider.appendDegrees(sb, lat)
    sb.append(']')
}

/**
 * The "burn" of one drive: all roads first driven in that drive, in drive order, burning as ONE travelling front at
 * constant speed over [durationMs].
 *
 * Duration: see [burnDurationMs] (the single source, shared with the summary animation).
 *
 * [frame] is cheap: per-segment lengths and the full-feature texts are computed once, a frame only concatenates texts
 * and clips the single segment the front is currently in.
 */
class BurnTimeline internal constructor(
    val driveId: Long,
    private val segments: List<BurnSegment>,
) {
    /** startM[i] = distance along the burn path where segment i starts. */
    private val startM = DoubleArray(segments.size)
    private val totalM: Double

    init {
        var acc = 0.0
        for (i in segments.indices) {
            startM[i] = acc
            acc += segments[i].lengthM
        }
        totalM = acc
    }

    /** 600..2000 ms, scaled by total fresh length. */
    val durationMs: Long = burnDurationMs(totalM)

    /** Bounds of the fresh roads; null if none. */
    val bounds: GeoBounds? = computeBounds()

    internal val segmentCount: Int get() = segments.size
    internal val totalLengthM: Double get() = totalM

    /** Ids of the fresh segments in burn order (diagnostics / tests). */
    internal val segmentIds: List<Long> get() = segments.map { it.id }

    /** Oriented vertices of the segment at [index] in burn order (tests). */
    internal fun segmentVertices(index: Int): Pair<DoubleArray, DoubleArray> = segments[index].lats to segments[index].lons

    /**
     * GeoJSON FeatureCollection for progress in [0,1]:
     *  - LineString features with property "kind":"burnt" = the already-burnt part of each fresh road, clipped
     *    mid-segment at the front;
     *  - one Point feature with "kind":"head" at the burn front while 0 < progress < 1 (absent at 0 and 1).
     * progress = 1 returns every fresh road fully, no head.
     */
    fun frame(progress: Float): String {
        val p = if (progress.isNaN()) 0.0 else progress.toDouble().coerceIn(0.0, 1.0)
        val sb = StringBuilder(if (p >= 1.0) segments.size * 200 + 64 else 1024)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var count = 0
        if (p >= 1.0) {
            for (s in segments) {
                if (count++ > 0) sb.append(',')
                sb.append(s.fullFeature)
            }
        } else if (p > 0.0) {
            val front = p * totalM
            var headLat = 0.0
            var headLon = 0.0
            var haveHead = false
            for (i in segments.indices) {
                val s = segments[i]
                val start = startM[i]
                if (start >= front) break
                val len = s.lengthM
                if (start + len <= front) {
                    if (count++ > 0) sb.append(',')
                    sb.append(s.fullFeature)
                    headLat = s.lats[s.vertexCount - 1]
                    headLon = s.lons[s.vertexCount - 1]
                    haveHead = true
                } else {
                    if (count++ > 0) sb.append(',')
                    val h = appendClipped(sb, s, front - start)
                    headLat = h[0]
                    headLon = h[1]
                    haveHead = true
                    break
                }
            }
            if (haveHead) {
                if (count > 0) sb.append(',')
                sb.append("{\"type\":\"Feature\",\"properties\":{\"kind\":\"head\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":")
                appendCoord(sb, headLon, headLat)
                sb.append("}}")
            }
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Appends [s] clipped at [distM] along it; returns the front point as [lat, lon]. */
    private fun appendClipped(sb: StringBuilder, s: BurnSegment, distM: Double): DoubleArray {
        openFeature(sb, s.id)
        var i = 1
        while (i < s.vertexCount - 1 && s.cumDist[i] < distM) i++
        // The front lies in the piece between vertex i-1 and i.
        val a = s.cumDist[i - 1]
        val b = s.cumDist[i]
        val t = if (b > a) ((distM - a) / (b - a)).coerceIn(0.0, 1.0) else 1.0
        val lat = s.lats[i - 1] + (s.lats[i] - s.lats[i - 1]) * t
        val lon = s.lons[i - 1] + (s.lons[i] - s.lons[i - 1]) * t
        for (k in 0 until i) {
            if (k > 0) sb.append(',')
            appendCoord(sb, s.lons[k], s.lats[k])
        }
        sb.append(',')
        appendCoord(sb, lon, lat)
        sb.append("]}}")
        return doubleArrayOf(lat, lon)
    }

    private fun computeBounds(): GeoBounds? {
        if (segments.isEmpty()) return null
        var west = Double.POSITIVE_INFINITY
        var south = Double.POSITIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY
        for (s in segments) for (i in 0 until s.vertexCount) {
            if (s.lons[i] < west) west = s.lons[i]
            if (s.lons[i] > east) east = s.lons[i]
            if (s.lats[i] < south) south = s.lats[i]
            if (s.lats[i] > north) north = s.lats[i]
        }
        return GeoBounds(west, south, east, north)
    }

    companion object {
        /**
         * Orders [roads] by where the drive passed them and builds the timeline. Each road is a (id, lats, lons) triple;
         * [fixLats]/[fixLons] are the drive's fixes in time order.
         *
         * Per road: the index of the nearest fix to its first and to its last vertex (the earliest index wins on equal
         * distance). Roads sort by (smaller index, larger index, id), so ties are deterministic. A road whose last vertex
         * is passed first is reversed so it burns along the direction of travel. Without fixes the roads keep id order.
         */
        internal fun build(
            driveId: Long,
            roads: List<Triple<Long, DoubleArray, DoubleArray>>,
            fixLats: DoubleArray,
            fixLons: DoubleArray,
        ): BurnTimeline {
            class Ordered(val id: Long, val lats: DoubleArray, val lons: DoubleArray, val lo: Int, val hi: Int, val reverse: Boolean)

            val usable = roads.filter { it.second.size >= 2 && it.second.size == it.third.size }
            val cosLat = if (fixLats.isEmpty()) 1.0 else cos(Geo.toRad(fixLats[fixLats.size / 2]))
            val ordered = usable.map { (id, lats, lons) ->
                val n = lats.size
                val candA = candidateFixes(lats[0], lons[0], fixLats, fixLons, cosLat)
                val candB = candidateFixes(lats[n - 1], lons[n - 1], fixLats, fixLons, cosLat)
                // Pick the pair of candidate passes that lie closest together in the trace (a road is driven in one go);
                // equal spans prefer the earlier pass.
                var a = candA[0]
                var b = candB[0]
                var span = Int.MAX_VALUE
                for (ca in candA) for (cb in candB) {
                    val d = abs(ca - cb)
                    if (d < span || (d == span && minOf(ca, cb) < minOf(a, b))) {
                        span = d
                        a = ca
                        b = cb
                    }
                }
                if (span > MAX_DRIVEN_SPAN_FIXES) {
                    // The trace never runs along this road (both ends are passed at unrelated times): place it where
                    // the trace passes its middle, keeping the stored direction.
                    val mid = middleVertex(lats, lons)
                    val m = nearestFix(lats[mid], lons[mid], fixLats, fixLons, cosLat)
                    Ordered(id, lats, lons, m, m, reverse = false)
                } else {
                    Ordered(id, lats, lons, minOf(a, b), maxOf(a, b), reverse = b < a)
                }
            }.sortedWith(compareBy<Ordered>({ it.lo }, { it.hi }, { it.id }))
            val segs = ordered.map {
                if (it.reverse) BurnSegment(it.id, it.lats.reversedArray(), it.lons.reversedArray())
                else BurnSegment(it.id, it.lats, it.lons)
            }.filter { it.lengthM > 0.0 }
            return BurnTimeline(driveId, segs)
        }

        /** A road whose two ends are passed more than this many fixes apart was not driven along its length. */
        private const val MAX_DRIVEN_SPAN_FIXES = 60

        /** Fix indices (ascending) within ~12 m of the nearest fix to the point; never empty when there are fixes. */
        private fun candidateFixes(lat: Double, lon: Double, fixLats: DoubleArray, fixLons: DoubleArray, cosLat: Double): IntArray {
            if (fixLats.isEmpty()) return intArrayOf(0)
            val best = nearestFix(lat, lon, fixLats, fixLons, cosLat)
            val bd = sqrt(sq(fixLats[best] - lat) + sq((fixLons[best] - lon) * cosLat))
            val limit = bd + 1.1e-4
            val out = ArrayList<Int>()
            for (i in fixLats.indices) {
                if (sq(fixLats[i] - lat) + sq((fixLons[i] - lon) * cosLat) <= limit * limit) out.add(i)
            }
            return out.toIntArray()
        }

        private fun sq(v: Double) = v * v

        private fun middleVertex(lats: DoubleArray, lons: DoubleArray): Int {
            var total = 0.0
            for (i in 1 until lats.size) total += Geo.distanceM(lats[i - 1], lons[i - 1], lats[i], lons[i])
            var acc = 0.0
            for (i in 1 until lats.size) {
                acc += Geo.distanceM(lats[i - 1], lons[i - 1], lats[i], lons[i])
                if (acc >= total / 2) return i
            }
            return lats.size / 2
        }

        private fun nearestFix(lat: Double, lon: Double, fixLats: DoubleArray, fixLons: DoubleArray, cosLat: Double): Int {
            var best = 0
            var bestD = Double.POSITIVE_INFINITY
            for (i in fixLats.indices) {
                val dy = fixLats[i] - lat
                val dx = (fixLons[i] - lon) * cosLat
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            return best
        }
    }
}
