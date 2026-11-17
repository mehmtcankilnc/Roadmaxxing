package app.roadmaxxing.shared.map

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.region.RoadLayer
import app.roadmaxxing.shared.region.Segment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToLong

/** Geographic box in degrees. */
data class GeoBounds(val west: Double, val south: Double, val east: Double, val north: Double)

/** Everything the map layer needs about the roads driven so far. */
data class DrivenRoadsSnapshot(
    /** FeatureCollection; one LineString feature per driven segment; properties: "id" (Long), "layer" ("major"|"local"), "fresh" (Boolean). */
    val geoJson: String,
    val segmentCount: Int,
    /** Segments first driven in the latest processed drive. */
    val freshSegmentCount: Int,
    /** Bounding box of all driven geometry; null when nothing is driven. */
    val drivenBounds: GeoBounds?,
)

/**
 * Builds the GeoJSON of driven roads for the map and exposes region bounding boxes.
 *
 * The heavy work (tile decoding, text building) runs on [dispatcher] inside [observe]. [provinceBounds] and
 * [regionBounds] block on their first call (they parse `regions.json`); [observe] warms them, so call them off
 * the main thread or after the first emission. Rendered feature text is cached per segment id, so a new drive
 * only renders its new segments.
 *
 * [RegionPack] is thread-safe itself; the mutex here only guards this class's own render cache.
 */
class DrivenRoadsProvider(
    private val packProvider: RegionPackProvider,
    private val store: DriveStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Rendered pieces of one segment: text up to the fresh flag value, text after it, and its bbox. */
    private class Rendered(
        val head: String,
        val tail: String,
        val west: Double,
        val south: Double,
        val east: Double,
        val north: Double,
    )

    private val cache = HashMap<Long, Rendered>()
    private val missing = HashSet<Long>()
    private val driveTiles = HashMap<Long, Set<Long>>()
    private val mutex = Mutex()

    private fun boundsOf(id: Long): GeoBounds? =
        packProvider.pack.regionBbox(id)?.let { GeoBounds(it.west, it.south, it.east, it.north) }

    /** Emits on start and whenever driven segments or the latest processed drive change. */
    fun observe(): Flow<DrivenRoadsSnapshot> =
        combine(store.observeDrivenSegmentCount(), store.observeLastProcessedDriveId()) { _, lastId -> lastId }
            .conflate()
            .map { lastId ->
                mutex.withLock { build(lastId) }
            }
            .flowOn(dispatcher)

    fun provinceBounds(): GeoBounds? = boundsOf(packProvider.pack.provinceId)

    fun regionBounds(regionId: Long): GeoBounds? = boundsOf(regionId)

    internal fun packForTest(): RegionPack = packProvider.pack

    /** Number of segments with cached text (diagnostics / tests). */
    val cachedSegmentCount: Int get() = cache.size

    private suspend fun build(lastProcessedDriveId: Long?): DrivenRoadsSnapshot {
        val firstDrives = store.drivenSegmentFirstDrives()
        val ids = firstDrives.keys.toLongArray().also { it.sort() }
        packProvider.pack
        prepare(ids, firstDrives)
        val fresh: Set<Long> =
            if (lastProcessedDriveId == null) emptySet() else store.segmentsFirstDrivenBy(lastProcessedDriveId)
        return render(ids, fresh)
    }

    /**
     * Renders the not yet cached segments of [ids] without the pack's whole-pack tile scan (which `RegionPack.segment`
     * needs for an unknown id and which costs seconds on a phone): a segment lies near the trace of the drive that first
     * drove it, so only the tiles around that drive's fixes are decoded. Ids not found there fall back to
     * [RegionPack.segment] in [render].
     */
    private suspend fun prepare(ids: LongArray, firstDrives: Map<Long, Long>) {
        val pack = packProvider.pack
        val wanted = HashSet<Long>()
        val drives = LinkedHashSet<Long>()
        for (id in ids) {
            if (id in cache || id in missing) continue
            val drive = firstDrives[id] ?: continue
            wanted.add(id)
            drives.add(drive)
        }
        if (wanted.isEmpty()) return
        val keys = LinkedHashSet<Long>()
        for (drive in drives) keys.addAll(tilesAround(drive))
        // Decode a few tiles at a time in parallel (RegionPack is thread-safe, decoding is pure); batches keep memory bounded.
        for (batch in keys.chunked(TILE_BATCH)) {
            if (wanted.isEmpty()) break
            val tiles = coroutineScope {
                batch.map { key -> async { pack.tile((key shr 32).toInt(), key.toInt()) } }.awaitAll()
            }
            for (tile in tiles) {
                if (tile == null) continue
                for (seg in tile.segments) {
                    if (seg.id in wanted && seg.vertexCount >= 2) {
                        cache[seg.id] = renderSegment(seg)
                        wanted.remove(seg.id)
                    }
                }
            }
        }
    }

    /** Tile keys (x shl 32 or y) within ~50 m of any fix of [driveId]; cached per drive. */
    private fun tilesAround(driveId: Long): Set<Long> = driveTiles.getOrPut(driveId) {
        val zoom = packProvider.pack.tileZoom
        val keys = LinkedHashSet<Long>()
        for (p in store.points(driveId, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val x0 = Geo.tileX(p.lon - TILE_MARGIN_DEG, zoom)
            val x1 = Geo.tileX(p.lon + TILE_MARGIN_DEG, zoom)
            val y0 = Geo.tileY(p.lat + TILE_MARGIN_DEG, zoom)
            val y1 = Geo.tileY(p.lat - TILE_MARGIN_DEG, zoom)
            for (x in x0..x1) for (y in y0..y1) keys.add((x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL))
        }
        keys
    }

    /** Renders [sortedIds]; ids in [fresh] get `"fresh":true`. Ids missing from the pack are skipped. Caller holds the lock. */
    internal fun render(sortedIds: LongArray, fresh: Set<Long>): DrivenRoadsSnapshot {
        val pack = packProvider.pack
        val sb = StringBuilder(sortedIds.size * 220 + 64)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var count = 0
        var freshCount = 0
        var west = Double.POSITIVE_INFINITY
        var south = Double.POSITIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY
        for (id in sortedIds) {
            val r = rendered(pack, id) ?: continue
            if (count > 0) sb.append(',')
            val isFresh = id in fresh
            sb.append(r.head).append(if (isFresh) "true" else "false").append(r.tail)
            count++
            if (isFresh) freshCount++
            if (r.west < west) west = r.west
            if (r.south < south) south = r.south
            if (r.east > east) east = r.east
            if (r.north > north) north = r.north
        }
        sb.append("]}")
        return DrivenRoadsSnapshot(
            geoJson = sb.toString(),
            segmentCount = count,
            freshSegmentCount = freshCount,
            drivenBounds = if (count == 0) null else GeoBounds(west, south, east, north),
        )
    }

    private fun rendered(pack: RegionPack, id: Long): Rendered? {
        cache[id]?.let { return it }
        if (id in missing) return null
        val seg = pack.segment(id)
        if (seg == null || seg.vertexCount < 2) {
            missing += id
            return null
        }
        return renderSegment(seg).also { cache[id] = it }
    }

    private fun renderSegment(seg: Segment): Rendered {
        val head = StringBuilder(96)
        head.append("{\"type\":\"Feature\",\"id\":").append(seg.id)
            .append(",\"properties\":{\"id\":").append(seg.id)
            .append(",\"layer\":\"").append(if (seg.layer == RoadLayer.Major) "major" else "local")
            .append("\",\"fresh\":")
        val tail = StringBuilder(seg.vertexCount * 20 + 48)
        tail.append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
        var west = Double.POSITIVE_INFINITY
        var south = Double.POSITIVE_INFINITY
        var east = Double.NEGATIVE_INFINITY
        var north = Double.NEGATIVE_INFINITY
        for (i in 0 until seg.vertexCount) {
            val lat = seg.lats[i]
            val lon = seg.lons[i]
            if (i > 0) tail.append(',')
            tail.append('[')
            appendDegrees(tail, lon)
            tail.append(',')
            appendDegrees(tail, lat)
            tail.append(']')
            if (lon < west) west = lon
            if (lon > east) east = lon
            if (lat < south) south = lat
            if (lat > north) north = lat
        }
        tail.append("]}}")
        return Rendered(head.toString(), tail.toString(), west, south, east, north)
    }

    internal companion object {
        private const val TILE_MARGIN_DEG = 0.0005
        private const val TILE_BATCH = 6

        /** Appends [v] with up to 6 decimals, trailing zeros trimmed (the pack stores 1e-6 degree precision). */
        fun appendDegrees(sb: StringBuilder, v: Double) {
            val micro = (abs(v) * 1_000_000.0).roundToLong()
            if (v < 0 && micro != 0L) sb.append('-')
            sb.append(micro / 1_000_000)
            var frac = (micro % 1_000_000).toInt()
            if (frac == 0) return
            var digits = 6
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
