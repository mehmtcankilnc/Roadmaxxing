package app.roadmaxxing.shared.matching

import app.roadmaxxing.shared.geo.LocalProjection
import app.roadmaxxing.shared.region.Oneway
import app.roadmaxxing.shared.region.Segment
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Growable int list without boxing. */
internal class IntList(capacity: Int = 4) {
    var data = IntArray(capacity)
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(max(4, size * 2))
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
}

/** Nearest point of one segment to a query point. */
internal class Projection(
    val seg: Int,
    val edge: Int,
    /** Metres from the segment's first vertex along its geometry (projected metres). */
    val offset: Double,
    val dist: Double,
    /** Bearing of the edge in geometry order, degrees clockwise from north. */
    val edgeBearing: Double,
)

/** Result of a bounded Dijkstra from one node: distances and the (segment, direction) code used to reach each node. */
internal class ShortestPaths(val source: Long, val bound: Double) {
    val dist = HashMap<Long, Double>()
    val via = HashMap<Long, Int>()
}

/**
 * Local road graph of the segments around one trace, in projected metres.
 *
 * Nodes are segment end nodes (OSM node ids); every segment is an edge usable in the directions its
 * `oneway` allows. A uniform grid over segment edges answers "segments near this point".
 */
internal class RoadNetwork(segments: List<Segment>, val proj: LocalProjection, private val cellSize: Double = 50.0) {
    val segs: Array<Segment> = segments.toTypedArray()
    val xs: Array<DoubleArray>
    val ys: Array<DoubleArray>
    val cum: Array<DoubleArray>
    val len: DoubleArray
    private val grid = HashMap<Long, IntList>()
    private val adjacency = HashMap<Long, IntList>()

    init {
        require(segs.size < (1 shl 20)) { "too many segments for one trace: ${segs.size}" }
        xs = Array(segs.size) { i -> DoubleArray(segs[i].vertexCount) { proj.x(segs[i].lons[it]) } }
        ys = Array(segs.size) { i -> DoubleArray(segs[i].vertexCount) { proj.y(segs[i].lats[it]) } }
        cum = Array(segs.size) { i ->
            val x = xs[i]
            val y = ys[i]
            val c = DoubleArray(x.size)
            for (k in 1 until x.size) {
                val dx = x[k] - x[k - 1]
                val dy = y[k] - y[k - 1]
                c[k] = c[k - 1] + sqrt(dx * dx + dy * dy)
            }
            c
        }
        len = DoubleArray(segs.size) { cum[it].last() }
        for (i in segs.indices) {
            val x = xs[i]
            val y = ys[i]
            for (e in 0 until x.size - 1) {
                val cx0 = cell(min(x[e], x[e + 1]))
                val cx1 = cell(max(x[e], x[e + 1]))
                val cy0 = cell(min(y[e], y[e + 1]))
                val cy1 = cell(max(y[e], y[e + 1]))
                val code = (i shl 11) or e
                for (cx in cx0..cx1) for (cy in cy0..cy1) grid.getOrPut(cellKey(cx, cy)) { IntList() }.add(code)
            }
            val s = segs[i]
            adjacency.getOrPut(s.nodeIds.first()) { IntList() }.add(i * 2) // enter at first node, travel forward
            adjacency.getOrPut(s.nodeIds.last()) { IntList() }.add(i * 2 + 1) // enter at last node, travel backward
        }
    }

    fun firstNode(seg: Int): Long = segs[seg].nodeIds.first()
    fun lastNode(seg: Int): Long = segs[seg].nodeIds.last()

    fun allows(seg: Int, dir: Int): Boolean = when (segs[seg].oneway) {
        Oneway.Both -> true
        Oneway.Forward -> dir == 0
        Oneway.Backward -> dir == 1
    }

    /** Best projection per segment within [radius] of (px, py), nearest first. */
    fun nearby(px: Double, py: Double, radius: Double): List<Projection> {
        val best = HashMap<Int, Projection>()
        val cx0 = cell(px - radius)
        val cx1 = cell(px + radius)
        val cy0 = cell(py - radius)
        val cy1 = cell(py + radius)
        for (cx in cx0..cx1) for (cy in cy0..cy1) {
            val list = grid[cellKey(cx, cy)] ?: continue
            for (k in 0 until list.size) {
                val code = list[k]
                val seg = code ushr 11
                val e = code and 0x7FF
                val p = project(seg, e, px, py)
                if (p.dist > radius) continue
                val old = best[seg]
                if (old == null || p.dist < old.dist) best[seg] = p
            }
        }
        return best.values.sortedBy { it.dist }
    }

    private fun project(seg: Int, e: Int, px: Double, py: Double): Projection {
        val x = xs[seg]
        val y = ys[seg]
        val ax = x[e]
        val ay = y[e]
        val dx = x[e + 1] - ax
        val dy = y[e + 1] - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 <= 1e-12) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        val qx = ax + t * dx
        val qy = ay + t * dy
        val ddx = px - qx
        val ddy = py - qy
        val bearing = (atan2(dx, dy) * 180.0 / kotlin.math.PI).let { if (it < 0) it + 360.0 else it }
        return Projection(seg, e, cum[seg][e] + t * sqrt(l2), sqrt(ddx * ddx + ddy * ddy), bearing)
    }

    /** Bounded Dijkstra over segment end nodes, respecting oneway. */
    fun shortestPaths(source: Long, bound: Double): ShortestPaths {
        val sp = ShortestPaths(source, bound)
        val heap = MinHeap()
        sp.dist[source] = 0.0
        heap.push(0.0, source)
        while (heap.isNotEmpty()) {
            val d = heap.topKey()
            val u = heap.pop()
            if (d > (sp.dist[u] ?: Double.MAX_VALUE)) continue
            val adj = adjacency[u] ?: continue
            for (k in 0 until adj.size) {
                val code = adj[k]
                val seg = code ushr 1
                val dir = code and 1
                if (!allows(seg, dir)) continue
                val v = if (dir == 0) lastNode(seg) else firstNode(seg)
                val nd = d + len[seg]
                if (nd > bound) continue
                if (nd < (sp.dist[v] ?: Double.MAX_VALUE)) {
                    sp.dist[v] = nd
                    sp.via[v] = code
                    heap.push(nd, v)
                }
            }
        }
        return sp
    }

    /** Segment-direction codes of the shortest path from [ShortestPaths.source] to [target], in travel order. */
    fun path(sp: ShortestPaths, target: Long): List<Int> {
        val out = ArrayList<Int>()
        var node = target
        var guard = 0
        while (node != sp.source && guard++ < 100_000) {
            val code = sp.via[node] ?: break
            out.add(code)
            val seg = code ushr 1
            node = if ((code and 1) == 0) firstNode(seg) else lastNode(seg)
        }
        out.reverse()
        return out
    }

    private fun cell(v: Double): Int = floor(v / cellSize).toInt()
    private fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
}

/** Binary min-heap of (Double key, Long value). */
internal class MinHeap {
    private var keys = DoubleArray(64)
    private var vals = LongArray(64)
    private var n = 0

    fun isNotEmpty(): Boolean = n > 0
    fun topKey(): Double = keys[0]

    fun push(k: Double, v: Long) {
        if (n == keys.size) {
            keys = keys.copyOf(n * 2)
            vals = vals.copyOf(n * 2)
        }
        var i = n++
        keys[i] = k
        vals[i] = v
        while (i > 0) {
            val p = (i - 1) / 2
            if (keys[p] <= keys[i]) break
            swap(i, p)
            i = p
        }
    }

    fun pop(): Long {
        val top = vals[0]
        n--
        if (n > 0) {
            keys[0] = keys[n]
            vals[0] = vals[n]
            var i = 0
            while (true) {
                val l = 2 * i + 1
                val r = l + 1
                var m = i
                if (l < n && keys[l] < keys[m]) m = l
                if (r < n && keys[r] < keys[m]) m = r
                if (m == i) break
                swap(i, m)
                i = m
            }
        }
        return top
    }

    private fun swap(a: Int, b: Int) {
        val k = keys[a]; keys[a] = keys[b]; keys[b] = k
        val v = vals[a]; vals[a] = vals[b]; vals[b] = v
    }
}
