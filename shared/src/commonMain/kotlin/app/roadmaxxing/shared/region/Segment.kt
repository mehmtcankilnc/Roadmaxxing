package app.roadmaxxing.shared.region

import app.roadmaxxing.shared.geo.LatLon

/** Allowed travel direction relative to the geometry (node) order. */
enum class Oneway { Both, Forward, Backward }

/**
 * A piece of one OSM way between two junctions (see tools/regions/FORMAT.md).
 * Geometry is stored as parallel coordinate arrays to keep memory low; [geometry] builds a list on demand.
 */
class Segment(
    val id: Long,
    val wayId: Long,
    val ordinal: Int,
    val lengthDm: Long,
    /** Frozen road class code 0..12 (FORMAT.md §2.3). */
    val roadClass: Int,
    val oneway: Oneway,
    val provinceId: Long,
    val districtId: Long,
    val lats: DoubleArray,
    val lons: DoubleArray,
    val nodeIds: LongArray,
) {
    val layer: RoadLayer get() = if (roadClass < 10) RoadLayer.Major else RoadLayer.Local
    val geometry: List<LatLon> get() = List(lats.size) { LatLon(lats[it], lons[it]) }
    val vertexCount: Int get() = lats.size

    override fun equals(other: Any?): Boolean = other is Segment && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "Segment($wayId:$ordinal)"

    companion object {
        fun idOf(wayId: Long, ordinal: Int): Long = wayId * 4096 + ordinal
    }
}
