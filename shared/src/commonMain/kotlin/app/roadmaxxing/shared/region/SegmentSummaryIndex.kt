package app.roadmaxxing.shared.region

/**
 * Geometry-free table of every segment of a pack, sorted by id (parallel arrays, binary search).
 * Decoded from `segments.bin` (FORMAT.md section 6) or, for packs without that file, built by
 * scanning the tiles. [tiles] (tile key per segment) is only known for the scanned variant.
 */
internal class SegmentSummaryIndex(
    val ids: LongArray,
    val lengthDm: LongArray,
    val roadClass: ByteArray,
    val provinceId: LongArray,
    val districtId: LongArray,
    val tiles: LongArray? = null,
) {
    fun summaryAt(pos: Int): SegmentSummary =
        SegmentSummary(ids[pos], lengthDm[pos], roadClass[pos].toInt(), provinceId[pos], districtId[pos])

    /** Position of [id] or -1. */
    fun find(id: Long): Int {
        var lo = 0
        var hi = ids.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = ids[mid]
            when {
                v < id -> lo = mid + 1
                v > id -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    companion object {
        private val MAGIC = intArrayOf(0x52, 0x4D, 0x58, 0x53) // "RMXS"
        private const val VERSION = 1

        /** Decodes `segments.bin`; [expectedCount] is the manifest's `segment_count`. */
        fun decode(bytes: ByteArray, expectedCount: Int): SegmentSummaryIndex {
            val c = ByteCursor(bytes)
            for (m in MAGIC) if (c.u8() != m) throw RegionPackFormatException("segments.bin: bad magic")
            val version = c.u8()
            if (version != VERSION) throw RegionPackFormatException("segments.bin: unsupported version $version")
            val regionCount = c.uvarintInt("regionCount")
            val regionIds = LongArray(regionCount) { c.uvarint() }
            val count = c.uvarintInt("segmentCount")
            if (count != expectedCount) {
                throw RegionPackFormatException("segments.bin lists $count segments, manifest says $expectedCount")
            }
            val ids = LongArray(count)
            val lengths = LongArray(count)
            val classes = ByteArray(count)
            val provinces = LongArray(count)
            val districts = LongArray(count)
            var id = 0L
            for (i in 0 until count) {
                id += c.uvarint()
                if (i > 0 && id <= ids[i - 1]) throw RegionPackFormatException("segments.bin: ids not ascending at record $i")
                ids[i] = id
                lengths[i] = c.uvarint()
                classes[i] = c.u8().toByte()
                val p = c.uvarintInt("provinceIdx")
                val d = c.uvarintInt("districtIdx")
                if (p >= regionCount || d >= regionCount) throw RegionPackFormatException("segments.bin: region index out of range")
                provinces[i] = regionIds[p]
                districts[i] = regionIds[d]
            }
            if (c.remaining != 0) throw RegionPackFormatException("segments.bin: ${c.remaining} trailing bytes")
            return SegmentSummaryIndex(ids, lengths, classes, provinces, districts)
        }
    }
}
