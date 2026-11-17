package app.roadmaxxing.shared.summary

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.region.Segment

/**
 * Finds the geometry of segments without scanning the whole pack (which `RegionPack.segment` needs for an unknown id
 * and which costs seconds on a phone): a segment lies near the trace of the drive that first drove it, so only the
 * tiles around that drive's fixes are decoded. Same approach as `DrivenRoadsProvider`.
 */
internal class SegmentResolver(private val pack: RegionPack, private val store: DriveStore) {

    /**
     * Segments of [ids] (id -> first drive id in [firstDrives]). Ids not found near their drive's trace are skipped
     * unless [scanForMissing] is set (then the slow whole-pack lookup is used for them).
     */
    fun resolve(ids: Collection<Long>, firstDrives: Map<Long, Long>, scanForMissing: Boolean): List<Segment> {
        val wanted = HashSet<Long>(ids)
        val byDrive = LinkedHashMap<Long, MutableList<Long>>()
        for (id in ids) firstDrives[id]?.let { byDrive.getOrPut(it) { ArrayList() }.add(id) }
        val out = ArrayList<Segment>()
        for ((driveId, _) in byDrive) {
            if (wanted.isEmpty()) break
            for (key in tilesAround(driveId)) {
                val tile = pack.tile((key shr 32).toInt(), key.toInt()) ?: continue
                for (seg in tile.segments) {
                    if (seg.vertexCount >= 2 && wanted.remove(seg.id)) out.add(seg)
                }
            }
        }
        if (scanForMissing) {
            for (id in wanted) pack.segment(id)?.takeIf { it.vertexCount >= 2 }?.let { out.add(it) }
        }
        return out
    }

    /** Tile keys (x shl 32 or y) within ~50 m of any fix of [driveId]. */
    private fun tilesAround(driveId: Long): Set<Long> {
        val zoom = pack.tileZoom
        val keys = LinkedHashSet<Long>()
        for (p in store.points(driveId, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val x0 = Geo.tileX(p.lon - TILE_MARGIN_DEG, zoom)
            val x1 = Geo.tileX(p.lon + TILE_MARGIN_DEG, zoom)
            val y0 = Geo.tileY(p.lat + TILE_MARGIN_DEG, zoom)
            val y1 = Geo.tileY(p.lat - TILE_MARGIN_DEG, zoom)
            for (x in x0..x1) for (y in y0..y1) keys.add((x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL))
        }
        return keys
    }

    private companion object {
        const val TILE_MARGIN_DEG = 0.0005
    }
}
