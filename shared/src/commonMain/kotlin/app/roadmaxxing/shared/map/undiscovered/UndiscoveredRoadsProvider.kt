package app.roadmaxxing.shared.map.undiscovered

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.map.GeoBounds
import app.roadmaxxing.shared.region.RoadLayer
import app.roadmaxxing.shared.region.Segment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * Roads of the region pack that count toward the percentages and are not driven yet, as GeoJSON for the map.
 *
 * "Counts toward the percentage" = every segment of the pack: region totals (`length_dm` in `regions.json`) are
 * built from all pack segments of both layers, and `ProgressCalculator` credits any pack segment by id, so there is
 * no further class filter; segments not in the pack never appear here.
 *
 * Only tiles intersecting the viewport (plus a small margin) are decoded; the pack-wide scan is never triggered.
 * Per (tile, detail tier) the rendered coordinate text is cached in an LRU of [MAX_CACHED_TILES] entries; the driven
 * set is cached and reloaded only when the driven segment count changes (driven roads are never removed).
 *
 * Size reduction (the map re-parses the whole string on a pan): coordinates have 5 decimals (~1 m), vertices are
 * simplified with Douglas-Peucker (5 m below zoom 13, 3 m at 13-15, 1.5 m from 15), and consecutive undriven
 * segments of the same way and layer are merged into one LineString. Features carry only `"layer":"major"|"local"`.
 *
 * Tile cap: at most [MAX_TILES] tiles are used. When the viewport needs more, the [MAX_TILES] tiles nearest to the
 * viewport centre are used and only major roads are returned (a zoomed-out view should not pay for local roads).
 */
class UndiscoveredRoadsProvider(
    private val packProvider: RegionPackProvider,
    private val store: DriveStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** One tile at one detail tier: per segment its coordinates `[x,y],[x,y]...` and merge info. */
    private class TileText(
        val ids: LongArray,
        val major: BooleanArray,
        val wayIds: LongArray,
        val firstNode: LongArray,
        val lastNode: LongArray,
        /** Coordinate text, or null when the segment collapsed to a point at this precision. */
        val coords: Array<String?>,
        /** Length of the first vertex text plus its comma (to drop it when merging), 0 for a one-vertex line. */
        val firstLen: IntArray,
        /** Segment bounding boxes, to leave out segments far outside the viewport. */
        val west: DoubleArray,
        val south: DoubleArray,
        val east: DoubleArray,
        val north: DoubleArray,
    )

    private val mutex = Mutex()
    private val tiles = LinkedHashMap<Long, TileText>() // insertion order = LRU order
    private var drivenCount = -1L
    private var driven: Set<Long> = emptySet()

    /** Tile entries rendered since creation (diagnostics / tests). */
    var renderedTileCount: Int = 0
        private set

    /** Tile entries currently cached (diagnostics / tests). */
    val cachedTileCount: Int get() = tiles.size

    /** Tile count used by the last call (diagnostics / tests). */
    var lastTileCount: Int = 0
        private set

    /**
     * GeoJSON FeatureCollection of pack segments that count toward the percentages, intersect [bounds] and are NOT driven.
     * LineString features, properties "layer":"major"|"local". zoom < 11 → empty collection; 11 ≤ zoom < 13 → major only; ≥ 13 → all.
     * Runs on its own dispatcher; safe to call often (cached).
     */
    suspend fun roadsIn(bounds: GeoBounds, zoom: Double): String {
        if (zoom < MIN_ZOOM) return EMPTY
        return withContext(dispatcher) { build(bounds, zoom) }
    }

    private suspend fun build(bounds: GeoBounds, zoom: Double): String {
        val pack = packProvider.pack
        val z = pack.tileZoom
        var majorOnly = zoom < ALL_LAYERS_ZOOM
        val tier = when {
            zoom < ALL_LAYERS_ZOOM -> 0
            zoom < FINE_ZOOM -> 1
            else -> 2
        }
        val x0 = Geo.tileX(bounds.west - MARGIN_DEG, z)
        val x1 = Geo.tileX(bounds.east + MARGIN_DEG, z)
        val y0 = Geo.tileY(bounds.north + MARGIN_DEG, z)
        val y1 = Geo.tileY(bounds.south - MARGIN_DEG, z)
        var keys = ArrayList<Long>()
        for (x in x0..x1) for (y in y0..y1) if (pack.hasTile(x, y)) keys.add(key(x, y))
        if (keys.size > MAX_TILES) {
            val cx = (x0 + x1) / 2.0
            val cy = (y0 + y1) / 2.0
            keys = ArrayList(
                keys.sortedBy { k ->
                    val dx = xOf(k) - cx
                    val dy = yOf(k) - cy
                    dx * dx + dy * dy
                }.take(MAX_TILES),
            )
            majorOnly = true
        }
        lastTileCount = keys.size
        if (keys.isEmpty()) return EMPTY

        // Decode and render missing tiles in parallel, outside the lock (pure work).
        val missing = mutex.withLock { keys.filter { cacheKey(it, tier) !in tiles } }
        if (missing.isNotEmpty()) {
            val rendered = coroutineScope {
                missing.chunked(TILE_BATCH).flatMap { batch ->
                    batch.map { k ->
                        async { k to pack.tile(xOf(k).toInt(), yOf(k).toInt())?.let { render(it.segments, tier) } }
                    }.awaitAll()
                }
            }
            mutex.withLock {
                for ((k, t) in rendered) if (t != null && cacheKey(k, tier) !in tiles) {
                    tiles[cacheKey(k, tier)] = t
                    renderedTileCount++
                }
            }
        }

        // Keep segments touching the viewport plus a 25% margin, so a small pan needs no new request.
        val padLon = (bounds.east - bounds.west) * VIEW_PAD
        val padLat = (bounds.north - bounds.south) * VIEW_PAD
        val vw = bounds.west - padLon
        val ve = bounds.east + padLon
        val vs = bounds.south - padLat
        val vn = bounds.north + padLat
        val count = store.observeDrivenSegmentCount().first()
        return mutex.withLock {
            if (count != drivenCount) {
                driven = store.drivenSegmentIds()
                drivenCount = count
            }
            val seen = HashSet<Long>()
            val sb = StringBuilder(64 * 1024)
            sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
            var wrote = false
            for (k in keys) {
                val ck = cacheKey(k, tier)
                val t = tiles.remove(ck) ?: continue
                tiles[ck] = t // touch
                // Pending merged line.
                var open = false
                var openWay = 0L
                var openMajor = false
                var openLast = 0L
                val line = StringBuilder(256)
                fun flush() {
                    if (!open) return
                    if (wrote) sb.append(',')
                    wrote = true
                    sb.append("{\"type\":\"Feature\",\"properties\":{\"layer\":\"")
                        .append(if (openMajor) "major" else "local")
                        .append("\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
                        .append(line).append("]}}")
                    line.setLength(0)
                    open = false
                }
                for (i in t.ids.indices) {
                    if (majorOnly && !t.major[i]) continue
                    if (t.east[i] < vw || t.west[i] > ve || t.north[i] < vs || t.south[i] > vn) {
                        flush()
                        continue
                    }
                    val id = t.ids[i]
                    val c = t.coords[i]
                    if (id in driven || c == null || !seen.add(id)) {
                        flush()
                        continue
                    }
                    if (open && t.wayIds[i] == openWay && t.major[i] == openMajor && t.firstNode[i] == openLast) {
                        line.append(',').appendRange(c, t.firstLen[i], c.length)
                    } else {
                        flush()
                        open = true
                        openWay = t.wayIds[i]
                        openMajor = t.major[i]
                        line.append(c)
                    }
                    openLast = t.lastNode[i]
                }
                flush()
            }
            sb.append("]}")
            while (tiles.size > MAX_CACHED_TILES) tiles.remove(tiles.keys.first())
            sb.toString()
        }
    }

    private fun render(segments: List<Segment>, tier: Int): TileText {
        val usable = segments.filter { it.vertexCount >= 2 }
        val n = usable.size
        val coords = arrayOfNulls<String>(n)
        val firstLen = IntArray(n)
        val tol = when (tier) {
            0 -> TOLERANCE_COARSE_M
            1 -> TOLERANCE_MEDIUM_M
            else -> TOLERANCE_FINE_M
        }
        val sb = StringBuilder(512)
        for (i in 0 until n) {
            sb.setLength(0)
            firstLen[i] = appendCoords(sb, usable[i], tol)
            if (firstLen[i] > 0) coords[i] = sb.toString()
        }
        return TileText(
            ids = LongArray(n) { usable[it].id },
            major = BooleanArray(n) { usable[it].layer == RoadLayer.Major },
            wayIds = LongArray(n) { usable[it].wayId },
            firstNode = LongArray(n) { usable[it].nodeIds.firstOrNull() ?: Long.MIN_VALUE },
            lastNode = LongArray(n) { usable[it].nodeIds.lastOrNull() ?: Long.MAX_VALUE },
            coords = coords,
            firstLen = firstLen,
            west = DoubleArray(n) { usable[it].lons.min() },
            south = DoubleArray(n) { usable[it].lats.min() },
            east = DoubleArray(n) { usable[it].lons.max() },
            north = DoubleArray(n) { usable[it].lats.max() },
        )
    }

    /**
     * Appends the simplified, 5-decimal vertices of [seg]; returns the length of the first vertex plus its comma,
     * or 0 when fewer than two distinct vertices remain.
     */
    private fun appendCoords(sb: StringBuilder, seg: Segment, toleranceM: Double): Int {
        val n = seg.vertexCount
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        if (n > 2) douglasPeucker(seg.lats, seg.lons, keep, toleranceM)
        var firstLen = 0
        var points = 0
        var lastLat = Long.MIN_VALUE
        var lastLon = Long.MIN_VALUE
        for (i in 0 until n) {
            if (!keep[i]) continue
            val la = (seg.lats[i] * 100_000.0).roundToLong()
            val lo = (seg.lons[i] * 100_000.0).roundToLong()
            if (la == lastLat && lo == lastLon) continue
            lastLat = la
            lastLon = lo
            if (points > 0) sb.append(',')
            val start = sb.length
            sb.append('[')
            appendMicro(sb, lo)
            sb.append(',')
            appendMicro(sb, la)
            sb.append(']')
            if (points == 0) firstLen = sb.length - start + 1
            points++
        }
        return if (points >= 2) firstLen else 0
    }

    private fun douglasPeucker(lats: DoubleArray, lons: DoubleArray, keep: BooleanArray, toleranceM: Double) {
        val n = lats.size
        val kx = cos(lats[n / 2] * DEG_TO_RAD) * M_PER_DEG
        val ky = M_PER_DEG
        val tol2 = toleranceM * toleranceM
        val stack = ArrayList<Int>()
        stack.add(0)
        stack.add(n - 1)
        while (stack.isNotEmpty()) {
            val b = stack.removeAt(stack.size - 1)
            val a = stack.removeAt(stack.size - 1)
            if (b - a < 2) continue
            val ax = lons[a] * kx
            val ay = lats[a] * ky
            val dx = lons[b] * kx - ax
            val dy = lats[b] * ky - ay
            val len2 = dx * dx + dy * dy
            var worst = -1.0
            var idx = -1
            for (i in a + 1 until b) {
                val px = lons[i] * kx - ax
                val py = lats[i] * ky - ay
                val d2 = if (len2 == 0.0) {
                    px * px + py * py
                } else {
                    val t = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
                    val ex = px - t * dx
                    val ey = py - t * dy
                    ex * ex + ey * ey
                }
                if (d2 > worst) {
                    worst = d2
                    idx = i
                }
            }
            if (worst > tol2 && idx > 0) {
                keep[idx] = true
                stack.add(a); stack.add(idx)
                stack.add(idx); stack.add(b)
            }
        }
    }

    internal companion object {
        const val MIN_ZOOM = 11.0
        const val ALL_LAYERS_ZOOM = 13.0
        const val FINE_ZOOM = 15.0
        const val MAX_TILES = 48
        const val MAX_CACHED_TILES = 64
        private const val TILE_BATCH = 6
        private const val MARGIN_DEG = 0.002
        private const val VIEW_PAD = 0.25
        private const val TOLERANCE_COARSE_M = 5.0
        private const val TOLERANCE_MEDIUM_M = 3.0
        private const val TOLERANCE_FINE_M = 1.5
        private const val M_PER_DEG = 111_320.0
        private const val DEG_TO_RAD = 0.017453292519943295
        private const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

        private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        private fun cacheKey(tileKey: Long, tier: Int): Long = tileKey * 4 + tier
        private fun xOf(k: Long): Long = k shr 32
        private fun yOf(k: Long): Long = k.toInt().toLong()

        /** Appends [units] (degrees times 1e5) as a decimal with trailing zeros trimmed. */
        private fun appendMicro(sb: StringBuilder, units: Long) {
            val a = abs(units)
            if (units < 0 && a != 0L) sb.append('-')
            sb.append(a / 100_000)
            var frac = (a % 100_000).toInt()
            if (frac == 0) return
            var digits = 5
            while (frac % 10 == 0) {
                frac /= 10
                digits--
            }
            sb.append('.')
            val s = frac.toString()
            repeat(digits - s.length) { sb.append('0') }
            sb.append(s)
        }
    }
}
