package app.roadmaxxing.shared.region

/** A decoded tile file (FORMAT.md §5.3). */
class DecodedTile(
    val zoom: Int,
    val x: Int,
    val y: Int,
    val coordDecimals: Int,
    val regionIds: LongArray,
    val segments: List<Segment>,
) {
    val vertexCount: Int get() = segments.sumOf { it.vertexCount }
}

/** Decoder of the binary tile format, version 1. Pure Kotlin, no platform API. */
object TileDecoder {
    const val FORMAT_VERSION: Int = 1
    private val MAGIC = byteArrayOf(0x52, 0x4D, 0x58, 0x54) // "RMXT"

    fun decode(bytes: ByteArray): DecodedTile {
        if (bytes.size < 5) throw RegionPackFormatException("tile too short (${bytes.size} bytes)")
        for (i in 0 until 4) if (bytes[i] != MAGIC[i]) throw RegionPackFormatException("not a Roadmaxxing tile (bad magic)")
        val c = ByteCursor(bytes, 4)
        val version = c.u8()
        if (version != FORMAT_VERSION) throw RegionPackFormatException("unsupported tile version $version")
        val zoom = c.u8()
        val x = c.uvarintInt("tile x")
        val y = c.uvarintInt("tile y")
        val coordDecimals = c.u8()
        if (coordDecimals > 9) throw RegionPackFormatException("coordDecimals $coordDecimals not supported")
        val scale = pow10(coordDecimals)
        val regionCount = c.uvarintInt("regionCount")
        if (regionCount > c.remaining) throw RegionPackFormatException("regionCount $regionCount exceeds tile size")
        val regionIds = LongArray(regionCount) { c.uvarint() }
        val segmentCount = c.uvarintInt("segmentCount")
        if (segmentCount > c.remaining) throw RegionPackFormatException("segmentCount $segmentCount exceeds tile size")
        val segments = ArrayList<Segment>(segmentCount)
        var wayId = 0L
        repeat(segmentCount) {
            wayId += c.uvarint()
            val ordinal = c.uvarintInt("ordinal")
            if (ordinal >= 4096) throw RegionPackFormatException("ordinal $ordinal >= 4096")
            val classOneway = c.u8()
            val roadClass = classOneway and 0x1F
            val onewayCode = (classOneway shr 5) and 0x03
            if (roadClass > 12) throw RegionPackFormatException("unknown road class $roadClass")
            if ((classOneway and 0x80) != 0) throw RegionPackFormatException("classOneway bit 7 set")
            val oneway = when (onewayCode) {
                0 -> Oneway.Both
                1 -> Oneway.Forward
                2 -> Oneway.Backward
                else -> throw RegionPackFormatException("unknown oneway code $onewayCode")
            }
            val provinceIdx = c.uvarintInt("provinceIdx")
            val districtIdx = c.uvarintInt("districtIdx")
            if (provinceIdx >= regionCount || districtIdx >= regionCount) {
                throw RegionPackFormatException("region index out of range")
            }
            val lengthDm = c.uvarint()
            val n = c.uvarintInt("nodeCount")
            if (n < 2) throw RegionPackFormatException("segment with $n nodes")
            if (n > c.remaining) throw RegionPackFormatException("nodeCount $n exceeds tile size")
            val nodeIds = LongArray(n)
            nodeIds[0] = c.uvarint()
            for (i in 1 until n) nodeIds[i] = nodeIds[i - 1] + c.svarint()
            val lats = DoubleArray(n)
            val lons = DoubleArray(n)
            var qLon = c.svarint()
            var qLat = c.svarint()
            lons[0] = qLon / scale
            lats[0] = qLat / scale
            for (i in 1 until n) {
                qLon += c.svarint()
                qLat += c.svarint()
                lons[i] = qLon / scale
                lats[i] = qLat / scale
            }
            segments.add(
                Segment(
                    id = Segment.idOf(wayId, ordinal),
                    wayId = wayId,
                    ordinal = ordinal,
                    lengthDm = lengthDm,
                    roadClass = roadClass,
                    oneway = oneway,
                    provinceId = regionIds[provinceIdx],
                    districtId = regionIds[districtIdx],
                    lats = lats,
                    lons = lons,
                    nodeIds = nodeIds,
                ),
            )
        }
        if (c.remaining != 0) throw RegionPackFormatException("trailing bytes in tile (${c.remaining})")
        return DecodedTile(zoom, x, y, coordDecimals, regionIds, segments)
    }

    private fun pow10(n: Int): Double {
        var r = 1.0
        repeat(n) { r *= 10.0 }
        return r
    }
}
