package app.roadmaxxing.shared.matching

import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.geo.LocalProjection
import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.region.RoadLayer
import app.roadmaxxing.shared.region.Segment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MatchedSegment(
    val segmentId: Long,
    /** Covered share of the segment's length, 0..1. */
    val coverage: Double,
    val lengthDm: Long,
    val layer: RoadLayer,
    val provinceId: Long,
    val districtId: Long,
)

data class MatchResult(
    /** coverage >= 0.70 only */
    val driven: List<MatchedSegment>,
    /** touched but below threshold */
    val partial: List<MatchedSegment>,
    /** share of trace points snapped to a road within tolerance */
    val matchedPointFraction: Double,
    val matchedDistanceM: Double,
) {
    companion object {
        val EMPTY = MatchResult(emptyList(), emptyList(), 0.0, 0.0)
    }
}

/**
 * Offline map matcher: snaps a finished drive to the road segments of a [RegionPack].
 *
 * Hidden Markov model (Newson & Krumm 2009) solved with Viterbi:
 * - states: (segment, travel direction, position along the segment) within a radius of each point;
 *   directions forbidden by `oneway` are not states, so the opposite carriageway of a divided road
 *   can only be chosen at a heading penalty;
 * - emission: Gaussian on the distance to the road, heading agreement (reported course, or derived
 *   from neighbouring points when missing), and a penalty for local roads at high speed;
 * - transition: |network route distance - straight-line distance| over a bounded Dijkstra on the
 *   segment graph; candidates with no network connection are linked by a costly "jump";
 * - coverage: along the Viterbi path, intervals of every traversed segment (including segments
 *   bridged by the route during GPS dropouts) are unioned; >= [MatcherConfig.drivenCoverage] = driven.
 *
 * Pure Kotlin; blocking and CPU-bound (call off the main thread). Shares the pack's tile cache,
 * so it inherits the pack's thread confinement.
 */
class RoadMatcher(
    private val pack: RegionPack,
    private val config: MatcherConfig = MatcherConfig(),
) {
    /** Test hook: receives one line per Viterbi step. */
    internal var debug: ((String) -> Unit)? = null

    fun match(trace: List<TracePoint>): MatchResult {
        if (trace.size < 2) return MatchResult.EMPTY
        val pts = trace.sortedBy { it.timeMs }
        val proj = LocalProjection(pts.sumOf { it.lat } / pts.size, pts.sumOf { it.lon } / pts.size)
        val net = RoadNetwork(loadSegments(pts), proj)
        return Run(pts, net).execute()
    }

    /** Segments of every tile within ~400 m of a trace point. */
    private fun loadSegments(pts: List<TracePoint>): List<Segment> {
        val z = pack.tileZoom
        val keys = LinkedHashSet<Long>()
        val marginM = 400.0
        for (p in pts) {
            val dLat = marginM / 111_000.0
            val dLon = marginM / (111_000.0 * max(0.1, cos(Geo.toRad(p.lat))))
            val x0 = Geo.tileX(p.lon - dLon, z)
            val x1 = Geo.tileX(p.lon + dLon, z)
            val y0 = Geo.tileY(p.lat + dLat, z)
            val y1 = Geo.tileY(p.lat - dLat, z)
            for (x in x0..x1) for (y in y0..y1) keys.add((x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL))
        }
        val seen = HashSet<Long>()
        val out = ArrayList<Segment>()
        for (k in keys) {
            val t = pack.tile((k shr 32).toInt(), k.toInt()) ?: continue
            for (s in t.segments) if (seen.add(s.id)) out.add(s)
        }
        return out
    }

    private class State(
        val seg: Int,
        val dir: Int,
        val offset: Double,
        val dist: Double,
        val emission: Double,
    )

    /** One HMM step = one kept trace point. */
    private class Step(val point: Int, val x: Double, val y: Double, val states: List<State>) {
        var score = DoubleArray(0)
        var back = IntArray(0)
        var jumpIn = BooleanArray(0)
    }

    private inner class Run(private val pts: List<TracePoint>, private val net: RoadNetwork) {
        private val px = DoubleArray(pts.size) { net.proj.x(pts[it].lon) }
        private val py = DoubleArray(pts.size) { net.proj.y(pts[it].lat) }
        private val pathCache = HashMap<Long, ShortestPaths>()

        fun execute(): MatchResult {
            // 1. Thin out: stops and crawling pile up points that carry only noise.
            val kept = ArrayList<Int>()
            val owner = IntArray(pts.size)
            for (i in pts.indices) {
                if (kept.isEmpty() || dist(i, kept.last()) >= config.minSpacingM) kept.add(i)
                owner[i] = kept.size - 1
            }

            // 2. Candidates per kept point.
            val steps = ArrayList<Step>(kept.size)
            for (k in kept.indices) {
                val i = kept[k]
                val p = pts[i]
                val sigma = (p.accuracyM?.takeIf { it > 0.0 } ?: config.defaultSigmaM)
                    .coerceIn(config.minSigmaM, config.maxSigmaM)
                val radius = max(config.minRadiusM, config.radiusSigmas * sigma)
                val headings = headings(kept, k)
                val speed = speed(kept, k)
                val states = ArrayList<State>()
                for (c in net.nearby(px[i], py[i], radius).take(config.maxCandidates)) {
                    for (dir in 0..1) {
                        if (!net.allows(c.seg, dir)) continue
                        val bearing = if (dir == 0) c.edgeBearing else Geo.normalizeDeg(c.edgeBearing + 180.0)
                        var e = -0.5 * (c.dist / sigma) * (c.dist / sigma)
                        if (headings.isNotEmpty()) {
                            val diff = headings.minOf { Geo.angleDiffDeg(it, bearing) }
                            e -= config.headingKappa * (1.0 - cos(diff * PI / 180.0))
                        }
                        if (speed != null && net.segs[c.seg].roadClass >= 10) {
                            e -= (config.localRoadPenaltyPerMps * (speed - config.localRoadFreeSpeedMps))
                                .coerceIn(0.0, config.localRoadMaxPenalty)
                        }
                        states.add(State(c.seg, dir, c.offset, c.dist, e))
                    }
                }
                steps.add(Step(i, px[i], py[i], states))
            }

            // 3. Viterbi over the steps that have candidates.
            val chain = steps.filter { it.states.isNotEmpty() }
            if (chain.isEmpty()) return MatchResult.EMPTY
            chain[0].apply {
                score = DoubleArray(states.size) { states[it].emission }
                back = IntArray(states.size) { -1 }
                jumpIn = BooleanArray(states.size) { true }
            }
            for (t in 1 until chain.size) {
                val prev = chain[t - 1]
                val cur = chain[t]
                val gc = hypot(cur.x - prev.x, cur.y - prev.y)
                val bound = min(5_000.0, max(250.0, 2.0 * gc + 150.0))
                val beta = config.transitionBetaM + config.transitionBetaPerM * gc
                val jumpCost = config.jumpPenalty + gc / 50.0
                cur.score = DoubleArray(cur.states.size) { Double.NEGATIVE_INFINITY }
                cur.back = IntArray(cur.states.size) { -1 }
                cur.jumpIn = BooleanArray(cur.states.size)
                var bestPrev = Double.NEGATIVE_INFINITY
                for (s in prev.score) bestPrev = max(bestPrev, s)
                for (j in cur.states.indices) {
                    val b = cur.states[j]
                    var best = Double.NEGATIVE_INFINITY
                    var arg = -1
                    var jump = false
                    for (i in prev.states.indices) {
                        val ps = prev.score[i]
                        if (ps < bestPrev - 60.0) continue // pruned: cannot win
                        val a = prev.states[i]
                        val d = routeDistance(a, b, bound)
                        val (lt, isJump) = if (d.isNaN()) {
                            -jumpCost to true
                        } else {
                            -abs(d - gc) / beta - config.segmentChangePenalty * boundariesCrossed(a, b, bound) -
                                config.backwardPenaltyPerM * backwardMetres(a, b) to false
                        }
                        val v = ps + lt
                        if (v > best) {
                            best = v
                            arg = i
                            jump = isJump
                        }
                    }
                    cur.score[j] = best + b.emission
                    cur.back[j] = arg
                    cur.jumpIn[j] = jump
                }
            }

            // 4. Backtrack.
            val chosen = IntArray(chain.size)
            var bi = chain.last().score.indices.maxBy { chain.last().score[it] }
            for (t in chain.indices.reversed()) {
                chosen[t] = bi
                bi = chain[t].back[bi].coerceAtLeast(0)
            }

            debug?.let { log ->
                for (t in chain.indices) {
                    val st = chain[t].states[chosen[t]]
                    val sg = net.segs[st.seg]
                    log("step $t pt=${chain[t].point} seg=${sg.wayId}:${sg.ordinal} dir=${st.dir} d=${st.dist.toInt()} " +
                        "jump=${chain[t].jumpIn[chosen[t]]} em=${(st.emission * 10).toInt() / 10.0} n=${chain[t].states.size}")
                }
            }

            // 5. Coverage intervals, matched distance and matched points along the path.
            val intervals = HashMap<Int, MutableList<DoubleArray>>()
            fun cover(seg: Int, a: Double, b: Double) {
                intervals.getOrPut(seg) { ArrayList() }.add(doubleArrayOf(min(a, b), max(a, b)))
            }
            val connected = BooleanArray(chain.size)
            var matchedDistance = 0.0
            for (t in 1 until chain.size) {
                val cs = chain[t].states[chosen[t]]
                if (chain[t].jumpIn[chosen[t]]) continue
                val ps = chain[t - 1].states[chosen[t - 1]]
                connected[t] = true
                connected[t - 1] = true
                val gc = hypot(chain[t].x - chain[t - 1].x, chain[t].y - chain[t - 1].y)
                val bound = min(5_000.0, max(250.0, 2.0 * gc + 150.0))
                if (backwardMetres(ps, cs) == 0.0) matchedDistance += routeDistance(ps, cs, bound).takeIf { !it.isNaN() } ?: 0.0
                coverTransition(ps, cs, bound, ::cover)
            }
            val matchedStep = BooleanArray(steps.size)
            run {
                var c = 0
                for (k in steps.indices) {
                    if (steps[k].states.isEmpty()) continue
                    val st = chain[c].states[chosen[c]]
                    matchedStep[k] = connected[c] && st.dist <= config.matchedToleranceM
                    c++
                }
            }
            var matchedPoints = 0
            for (i in pts.indices) if (matchedStep[owner[i]]) matchedPoints++

            val driven = ArrayList<MatchedSegment>()
            val partial = ArrayList<MatchedSegment>()
            for ((seg, list) in intervals) {
                val len = net.len[seg]
                val covered = unionLength(list, len)
                val coverage = if (len <= 0.0) 1.0 else (covered / len).coerceIn(0.0, 1.0)
                if (coverage <= 0.0) continue
                val s = net.segs[seg]
                val m = MatchedSegment(s.id, coverage, s.lengthDm, s.layer, s.provinceId, s.districtId)
                if (coverage >= config.drivenCoverage) driven.add(m) else partial.add(m)
            }
            driven.sortBy { it.segmentId }
            partial.sortBy { it.segmentId }
            return MatchResult(driven, partial, matchedPoints.toDouble() / pts.size, matchedDistance)
        }

        /** Network distance from state a to state b in travel direction, NaN when not reachable within [bound]. */
        private fun routeDistance(a: State, b: State, bound: Double): Double {
            if (a.seg == b.seg && a.dir == b.dir) {
                val delta = if (a.dir == 0) b.offset - a.offset else a.offset - b.offset
                if (delta >= -config.backwardToleranceM) return abs(delta)
            }
            val remA = remaining(a)
            val entB = entered(b)
            if (remA + entB > bound) return Double.NaN
            val sp = paths(exitNode(a), bound - remA)
            val dn = sp.dist[entryNode(b)] ?: return Double.NaN
            val total = remA + dn + entB
            return if (total > bound) Double.NaN else total
        }

        /** Metres moved against the travel direction within one segment (GPS jitter), 0 otherwise. */
        private fun backwardMetres(a: State, b: State): Double {
            if (a.seg != b.seg || a.dir != b.dir) return 0.0
            val delta = if (a.dir == 0) b.offset - a.offset else a.offset - b.offset
            return if (delta < 0 && delta >= -config.backwardToleranceM) -delta else 0.0
        }

        /** Number of segment boundaries a reachable transition crosses (0 when it stays on one segment). */
        private fun boundariesCrossed(a: State, b: State, bound: Double): Int {
            if (a.seg == b.seg && a.dir == b.dir) {
                val delta = if (a.dir == 0) b.offset - a.offset else a.offset - b.offset
                if (delta >= -config.backwardToleranceM) return 0
            }
            val sp = paths(exitNode(a), bound - remaining(a))
            return 1 + pathLength(sp, entryNode(b))
        }

        private val hopCache = HashMap<ShortestPaths, HashMap<Long, Int>>()

        private fun pathLength(sp: ShortestPaths, target: Long): Int {
            val m = hopCache.getOrPut(sp) { HashMap() }
            m[target]?.let { return it }
            val n = net.path(sp, target).size
            m[target] = n
            return n
        }

        private fun coverTransition(a: State, b: State, bound: Double, cover: (Int, Double, Double) -> Unit) {
            if (a.seg == b.seg && a.dir == b.dir) {
                val delta = if (a.dir == 0) b.offset - a.offset else a.offset - b.offset
                if (delta >= -config.backwardToleranceM) {
                    if (delta > 0) cover(a.seg, a.offset, b.offset)
                    return
                }
            }
            val remA = remaining(a)
            val sp = paths(exitNode(a), bound - remA)
            val target = entryNode(b)
            if (sp.dist[target] == null) return
            cover(a.seg, a.offset, if (a.dir == 0) net.len[a.seg] else 0.0)
            for (code in net.path(sp, target)) {
                val seg = code ushr 1
                cover(seg, 0.0, net.len[seg])
            }
            cover(b.seg, if (b.dir == 0) 0.0 else net.len[b.seg], b.offset)
        }

        private fun remaining(s: State): Double = if (s.dir == 0) net.len[s.seg] - s.offset else s.offset
        private fun entered(s: State): Double = if (s.dir == 0) s.offset else net.len[s.seg] - s.offset
        private fun exitNode(s: State): Long = if (s.dir == 0) net.lastNode(s.seg) else net.firstNode(s.seg)
        private fun entryNode(s: State): Long = if (s.dir == 0) net.firstNode(s.seg) else net.lastNode(s.seg)

        private fun paths(source: Long, bound: Double): ShortestPaths {
            val cached = pathCache[source]
            if (cached != null && cached.bound >= bound) return cached
            if (pathCache.size > 2_000) {
                pathCache.clear()
                hopCache.clear()
            }
            val sp = net.shortestPaths(source, max(bound, 500.0))
            pathCache[source] = sp
            return sp
        }

        /**
         * Trusted headings for kept point k: the reported course when it agrees with the motion between
         * neighbouring points, else those motion bearings. Some sources report a course that is not one
         * (the Android emulator sends 0 or stale values), so a reported course never overrides the motion.
         */
        private fun headings(kept: List<Int>, k: Int): List<Double> {
            val i = kept[k]
            val p = pts[i]
            val reportedSpeed = p.speedMps
            if (reportedSpeed != null && reportedSpeed < config.minHeadingSpeedMps) return emptyList()
            val derived = ArrayList<Double>(2)
            if (k > 0) derivedBearing(kept[k - 1], i)?.let { derived.add(it) }
            if (k + 1 < kept.size) derivedBearing(i, kept[k + 1])?.let { derived.add(it) }
            val reported = p.bearingDeg ?: return derived
            if (derived.isEmpty()) return listOf(reported)
            val agrees = derived.minOf { Geo.angleDiffDeg(it, reported) } <= config.maxReportedHeadingDisagreementDeg
            return if (agrees) listOf(reported) else derived
        }

        private fun derivedBearing(a: Int, b: Int): Double? {
            val d = dist(a, b)
            val dt = (pts[b].timeMs - pts[a].timeMs) / 1000.0
            if (d < config.minSpacingM || dt <= 0.0 || d / dt < config.minHeadingSpeedMps) return null
            val deg = atan2(px[b] - px[a], py[b] - py[a]) * 180.0 / PI
            return Geo.normalizeDeg(deg)
        }

        private fun speed(kept: List<Int>, k: Int): Double? {
            pts[kept[k]].speedMps?.let { return it }
            val a = kept[max(0, k - 1)]
            val b = kept[min(kept.size - 1, k + 1)]
            val dt = (pts[b].timeMs - pts[a].timeMs) / 1000.0
            return if (dt <= 0.0) null else dist(a, b) / dt
        }

        private fun dist(a: Int, b: Int): Double = hypot(px[a] - px[b], py[a] - py[b])
    }

    private fun hypot(dx: Double, dy: Double): Double = sqrt(dx * dx + dy * dy)

    companion object {
        /** Length of the union of [a, b] intervals clipped to [0, len]. */
        internal fun unionLength(list: List<DoubleArray>, len: Double): Double {
            val sorted = list.map { doubleArrayOf(it[0].coerceIn(0.0, len), it[1].coerceIn(0.0, len)) }.sortedBy { it[0] }
            var total = 0.0
            var curStart = Double.NaN
            var curEnd = Double.NaN
            for (iv in sorted) {
                if (curStart.isNaN()) {
                    curStart = iv[0]; curEnd = iv[1]
                } else if (iv[0] <= curEnd) {
                    curEnd = max(curEnd, iv[1])
                } else {
                    total += curEnd - curStart
                    curStart = iv[0]; curEnd = iv[1]
                }
            }
            if (!curStart.isNaN()) total += curEnd - curStart
            return total
        }
    }
}
