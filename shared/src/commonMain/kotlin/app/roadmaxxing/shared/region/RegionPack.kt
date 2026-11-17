package app.roadmaxxing.shared.region

import app.roadmaxxing.shared.geo.Geo
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A region pack (format v1, tools/regions/FORMAT.md).
 *
 * `manifest.json` and `regions.json` are read eagerly; tiles are decoded on demand and kept in an
 * LRU cache bounded by decoded vertex count ([maxCachedVertices], ~24 bytes per vertex).
 * Unknown format versions are rejected with [RegionPackFormatException].
 *
 * Thread-safe. `commonMain` has no blocking lock and no new library may be added, so the mutable state is
 * guarded like this: the tile cache (a [LinkedHashMap] plus its vertex counter) by a tiny spin lock that is only
 * held for map operations, never while a tile is read or decoded (decoding is pure, so it runs outside the
 * lock and a racing duplicate decode is simply discarded); the lazily built summary index, the diagnostics
 * counters and [summaryFromFile] by atomics, with the immutable index published as a whole. Two threads that
 * need the index at the same moment may both build it; the results are equal and the first published wins.
 * All calls are blocking and CPU/IO-bound; call them off the main thread.
 */
@OptIn(ExperimentalAtomicApi::class)
class RegionPack(
    private val source: RegionPackSource,
    private val maxCachedVertices: Int = DEFAULT_MAX_CACHED_VERTICES,
) {
    val packId: String
    val provinceId: Long
    val tileZoom: Int

    /** Distinct segments in the pack, from the manifest. */
    val segmentCount: Int

    /** Province first, then districts by ascending id. */
    val regions: List<Region>

    private val tilePathTemplate: String
    private val regionBoxes: Map<Long, RegionBbox>

    /** Tile key ([tileKey]) -> segment count listed in the manifest. */
    private val tileIndex: Map<Long, Int>

    /** Insertion order = LRU order (re-inserted on every hit). Guarded by [cacheLock], with [cachedVertices]. */
    private val cache = LinkedHashMap<Long, DecodedTile>()
    private var cachedVertices = 0
    private val cacheLock = SpinLock()
    private val summaryIndexRef = AtomicReference<SegmentSummaryIndex?>(null)

    /** Path of `segments.bin` from the manifest, or null for packs without a segment summary index. */
    private val segmentIndexPath: String?

    /** Whether the summary index came from `segments.bin` (true) or from scanning tiles (false); null before first use. */
    val summaryFromFile: Boolean? get() = summaryFromFileRef.load()
    private val summaryFromFileRef = AtomicReference<Boolean?>(null)

    private val tileLoadCount = AtomicInt(0)

    /** Number of tile decodes so far (diagnostics / tests). */
    val tileLoads: Int get() = tileLoadCount.load()

    init {
        val manifest = parseJson(MANIFEST)
        checkFormat(manifest, "roadmaxxing-region-pack", MANIFEST)
        packId = manifest.string("pack_id") ?: throw RegionPackFormatException("pack_id missing")
        provinceId = manifest.requireLong("province_id")
        tileZoom = manifest.requireLong("tile_zoom").toInt()
        if (tileZoom !in 0..22) throw RegionPackFormatException("tile_zoom $tileZoom out of range")
        segmentCount = manifest.requireLong("segment_count").toInt()
        segmentIndexPath = (manifest["segment_index"] as? JsonObject)?.string("path")
        tilePathTemplate = manifest.string("tile_path") ?: "tiles/{z}/{x}/{y}.bin"
        val tiles = HashMap<Long, Int>()
        val tilesArr = manifest["tiles"] as? JsonArray ?: throw RegionPackFormatException("tiles missing")
        for (t in tilesArr) {
            val a = t.jsonArray
            val x = a[0].jsonPrimitive.intOrNull ?: throw RegionPackFormatException("bad tile entry $a")
            val y = a[1].jsonPrimitive.intOrNull ?: throw RegionPackFormatException("bad tile entry $a")
            tiles[tileKey(x, y)] = a.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 0
        }
        tileIndex = tiles

        val regionsJson = parseJson(REGIONS)
        checkFormat(regionsJson, "roadmaxxing-regions", REGIONS)
        val list = regionsJson["regions"] as? JsonArray ?: throw RegionPackFormatException("regions missing")
        val boxes = HashMap<Long, RegionBbox>()
        regions = list.map { el ->
            val o = el.jsonObject
            val len = o["length_dm"] as? JsonObject ?: throw RegionPackFormatException("length_dm missing")
            val id = o.requireLong("id")
            (o["bbox"] as? JsonArray)?.takeIf { it.size == 4 }?.let { b ->
                val v = b.map { (it as? JsonPrimitive)?.doubleOrNull }
                if (v.none { it == null }) boxes[id] = RegionBbox(v[0]!!, v[1]!!, v[2]!!, v[3]!!)
            }
            Region(
                id = id,
                level = o.requireLong("level").toInt(),
                parentId = (o["parent_id"] as? JsonPrimitive)?.longOrNull,
                name = o.string("name") ?: o.string("name_tr") ?: o.string("name_en") ?: id.toString(),
                nameTr = o.string("name_tr"),
                nameEn = o.string("name_en"),
                lengthDmMajor = len.requireLong("major"),
                lengthDmLocal = len.requireLong("local"),
            )
        }
        regionBoxes = boxes
        if (regions.none { it.id == provinceId && it.level == 1 }) {
            throw RegionPackFormatException("province $provinceId not in $REGIONS")
        }
    }

    val province: Region get() = regions.first { it.id == provinceId }
    val districts: List<Region> get() = regions.filter { it.level == 2 }

    /** Bounding box of a region from `regions.json` (no boundary parsing needed), or null. */
    fun regionBbox(id: Long): RegionBbox? = regionBoxes[id]

    fun region(id: Long): Region? = regions.firstOrNull { it.id == id }

    /** Whether the manifest lists tile (x, y). */
    fun hasTile(x: Int, y: Int): Boolean = tileKey(x, y) in tileIndex

    /** Number of tiles currently decoded in memory (diagnostics / tests). */
    val cachedTileCount: Int get() = cacheLock.withLock { cache.size }

    /** Decoded vertices currently held by the cache (diagnostics / tests). */
    val cachedVertexCount: Int get() = cacheLock.withLock { cachedVertices }

    /** Decoded tile (x, y) at [tileZoom], or null when the pack has no such tile. Cached (LRU). */
    fun tile(x: Int, y: Int): DecodedTile? {
        val key = tileKey(x, y)
        cachedTile(key, touch = true)?.let { return it }
        if (key !in tileIndex) return null
        val decoded = decode(key) // outside the lock: pure and slow
        return cacheLock.withLock {
            val raced = cache.remove(key)
            if (raced != null) {
                cache[key] = raced
                raced
            } else {
                cache[key] = decoded
                cachedVertices += decoded.vertexCount
                evict()
                decoded
            }
        }
    }

    /** Cached tile or null; [touch] moves it to the most recently used position. */
    private fun cachedTile(key: Long, touch: Boolean): DecodedTile? = cacheLock.withLock {
        if (touch) cache.remove(key)?.also { cache[key] = it } else cache[key]
    }

    /** Segments of all tiles intersecting the lat/lon box, de-duplicated by id. */
    fun segmentsInBox(south: Double, west: Double, north: Double, east: Double): List<Segment> {
        val x0 = Geo.tileX(west, tileZoom)
        val x1 = Geo.tileX(east, tileZoom)
        val y0 = Geo.tileY(north, tileZoom)
        val y1 = Geo.tileY(south, tileZoom)
        val seen = HashSet<Long>()
        val out = ArrayList<Segment>()
        for (x in x0..x1) for (y in y0..y1) {
            val t = tile(x, y) ?: continue
            for (s in t.segments) if (seen.add(s.id)) out.add(s)
        }
        return out
    }

    /** Segment by id (loads its tile; an uncached id needs the tile locator, built by scanning every tile once), or null when not in the pack. */
    fun segment(id: Long): Segment? {
        val cached = cacheLock.withLock { cache.values.toList() }
        for (t in cached) t.segments.binarySearchById(id)?.let { return it }
        if (summaryIndexRef.load()?.find(id) == -1) return null
        val idx = scannedIndex()
        val pos = idx.find(id)
        if (pos < 0) return null
        val key = idx.tiles!![pos]
        return tile(tileXOf(key), tileYOf(key))?.segments?.binarySearchById(id)
    }

    /** Geometry-free summary of a segment, or null when not in the pack. Reads `segments.bin` once (no tiles). */
    fun summary(id: Long): SegmentSummary? {
        val idx = index()
        val pos = idx.find(id)
        return if (pos >= 0) idx.summaryAt(pos) else null
    }

    /** All distinct segment ids of the pack, ascending. Reads the summary index (no tiles when `segments.bin` exists). */
    fun allSegmentIds(): LongArray = index().ids.copyOf()

    /** Visits every distinct segment once, in tile order (reads every tile without filling the cache). */
    fun forEachSegment(action: (Segment) -> Unit) {
        val seen = HashSet<Long>()
        for (key in tileIndex.keys.sorted()) {
            val t = cachedTile(key, touch = false) ?: decode(key)
            for (s in t.segments) if (seen.add(s.id)) action(s)
        }
    }

    /** Summary index of the whole pack: from `segments.bin` when the manifest lists it, else scanned from the tiles. */
    private fun index(): SegmentSummaryIndex {
        summaryIndexRef.load()?.let { return it }
        val path = segmentIndexPath
        if (path != null) {
            val bytes = source.read(path) ?: throw RegionPackFormatException("$path listed in manifest but missing")
            val built = SegmentSummaryIndex.decode(bytes, segmentCount)
            if (summaryIndexRef.compareAndSet(null, built)) summaryFromFileRef.store(true)
            return summaryIndexRef.load() ?: built
        }
        return scannedIndex()
    }

    /**
     * Summary index plus tile locator built by streaming every tile through the decoder without
     * caching them (~92k segments for Adana, about 4 MB). Fallback for packs without `segments.bin`,
     * and the only source of "which tile holds this id" for [segment].
     */
    private fun scannedIndex(): SegmentSummaryIndex {
        summaryIndexRef.load()?.takeIf { it.tiles != null }?.let { return it }
        val summaries = HashMap<Long, SegmentSummary>(segmentCount * 2)
        val tileOf = HashMap<Long, Long>(segmentCount * 2)
        for (key in tileIndex.keys.sorted()) {
            val t = cachedTile(key, touch = false) ?: decode(key)
            for (s in t.segments) {
                if (s.id in summaries) continue
                summaries[s.id] = SegmentSummary(s.id, s.lengthDm, s.roadClass, s.provinceId, s.districtId)
                tileOf[s.id] = key
            }
        }
        val ids = summaries.keys.toLongArray().also { it.sort() }
        val built = SegmentSummaryIndex(
            ids = ids,
            lengthDm = LongArray(ids.size) { summaries.getValue(ids[it]).lengthDm },
            roadClass = ByteArray(ids.size) { summaries.getValue(ids[it]).roadClass.toByte() },
            provinceId = LongArray(ids.size) { summaries.getValue(ids[it]).provinceId },
            districtId = LongArray(ids.size) { summaries.getValue(ids[it]).districtId },
            tiles = LongArray(ids.size) { tileOf.getValue(ids[it]) },
        )
        // Publish: a file-based index (no tile locator) is replaced; a scanned one from a racing thread is kept.
        while (true) {
            val current = summaryIndexRef.load()
            if (current?.tiles != null) return current
            if (summaryIndexRef.compareAndSet(current, built)) {
                if (current == null) summaryFromFileRef.store(false)
                return built
            }
        }
    }

    private fun decode(key: Long): DecodedTile {
        val x = tileXOf(key)
        val y = tileYOf(key)
        val path = tilePathTemplate.replace("{z}", tileZoom.toString())
            .replace("{x}", x.toString()).replace("{y}", y.toString())
        val bytes = source.read(path) ?: throw RegionPackFormatException("tile $path listed in manifest but missing")
        val decoded = TileDecoder.decode(bytes)
        tileLoadCount.incrementAndFetch()
        if (decoded.zoom != tileZoom || decoded.x != x || decoded.y != y) {
            throw RegionPackFormatException("tile $path header says ${decoded.zoom}/${decoded.x}/${decoded.y}")
        }
        return decoded
    }

    private fun evict() {
        while (cachedVertices > maxCachedVertices && cache.size > 1) {
            val eldest = cache.keys.first()
            val t = cache.remove(eldest) ?: break
            cachedVertices -= t.vertexCount
        }
    }

    private fun parseJson(name: String): JsonObject {
        val bytes = source.read(name) ?: throw RegionPackFormatException("$name missing")
        return try {
            json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (e: IllegalArgumentException) {
            throw RegionPackFormatException("$name is not a valid JSON object: ${e.message}")
        }
    }

    companion object {
        const val FORMAT_VERSION: Int = 1

        /** ~10 MB of decoded geometry; the largest Adana tile has ~25k vertices. */
        const val DEFAULT_MAX_CACHED_VERTICES: Int = 400_000
        private const val MANIFEST = "manifest.json"
        private const val REGIONS = "regions.json"

        internal fun tileKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        internal fun tileXOf(key: Long): Int = (key shr 32).toInt()
        internal fun tileYOf(key: Long): Int = key.toInt()

        private val json = Json { ignoreUnknownKeys = true }

        private fun checkFormat(o: JsonObject, expected: String, name: String) {
            val format = o.string("format")
            if (format != expected) throw RegionPackFormatException("$name: format '$format', expected '$expected'")
            val version = (o["format_version"] as? JsonPrimitive)?.longOrNull
            if (version != FORMAT_VERSION.toLong()) {
                throw RegionPackFormatException("$name: unsupported format_version $version")
            }
        }

        private fun JsonObject.string(key: String): String? = when (val v = this[key]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (v.isString) v.contentOrNull else null
            else -> null
        }

        private fun JsonObject.requireLong(key: String): Long =
            (this[key] as? JsonPrimitive)?.longOrNull ?: throw RegionPackFormatException("$key missing or not an integer")
    }
}

/** `[west, south, east, north]` of a region in degrees. */
data class RegionBbox(val west: Double, val south: Double, val east: Double, val north: Double)

/** Geometry-free facts about a segment, enough for progress computation. */
data class SegmentSummary(
    val id: Long,
    val lengthDm: Long,
    val roadClass: Int,
    val provinceId: Long,
    val districtId: Long,
) {
    val layer: RoadLayer get() = if (roadClass < 10) RoadLayer.Major else RoadLayer.Local
}

/** Tile segments are sorted by (wayId, ordinal), i.e. by id. */
private fun List<Segment>.binarySearchById(id: Long): Segment? {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = this[mid].id
        when {
            v < id -> lo = mid + 1
            v > id -> hi = mid - 1
            else -> return this[mid]
        }
    }
    return null
}

/**
 * Minimal spin lock for very short critical sections (map bookkeeping, microseconds); `commonMain` offers no
 * blocking lock. Never hold it across IO or decoding.
 */
@OptIn(ExperimentalAtomicApi::class)
private class SpinLock {
    private val state = AtomicInt(0)

    inline fun <T> withLock(block: () -> T): T {
        while (!state.compareAndSet(0, 1)) { /* spin */ }
        try {
            return block()
        } finally {
            state.store(0)
        }
    }
}
