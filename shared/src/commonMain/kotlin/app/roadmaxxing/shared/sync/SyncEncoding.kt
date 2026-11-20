package app.roadmaxxing.shared.sync

import app.roadmaxxing.shared.summary.GeoPoint
import kotlin.math.roundToLong

/**
 * Compact text encodings used in synced documents. Both use the alphabet of Google's encoded polyline format
 * (characters 63..126): 5 data bits per character, bit 0x20 = "more characters follow".
 */
object SyncEncoding {
    private const val ROUTE_SCALE = 1e5

    /** Route as delta-encoded polyline at 1e-5 degrees (about 1.1 m). */
    fun encodeRoute(points: List<GeoPoint>): String {
        val sb = StringBuilder(points.size * 4)
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * ROUTE_SCALE).roundToLong()
            val lon = (p.lon * ROUTE_SCALE).roundToLong()
            writeVarint(sb, zigzag(lat - prevLat))
            writeVarint(sb, zigzag(lon - prevLon))
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    /** Inverse of [encodeRoute]; null when the text is malformed. */
    fun decodeRoute(text: String): List<GeoPoint>? {
        val values = readVarints(text) ?: return null
        if (values.size % 2 != 0) return null
        val out = ArrayList<GeoPoint>(values.size / 2)
        var lat = 0L
        var lon = 0L
        var i = 0
        while (i < values.size) {
            lat += unzigzag(values[i])
            lon += unzigzag(values[i + 1])
            out.add(GeoPoint(lat / ROUTE_SCALE, lon / ROUTE_SCALE))
            i += 2
        }
        return out
    }

    /** Segment ids as a sorted, de-duplicated, delta-encoded varint string. */
    fun encodeIds(ids: Collection<Long>): String {
        val sorted = ids.distinct().sorted()
        val sb = StringBuilder(sorted.size * 3)
        var prev = 0L
        for (id in sorted) {
            require(id >= 0) { "negative segment id" }
            writeVarint(sb, id - prev)
            prev = id
        }
        return sb.toString()
    }

    /** Inverse of [encodeIds] (ascending order); null when the text is malformed. */
    fun decodeIds(text: String): List<Long>? {
        val deltas = readVarints(text) ?: return null
        val out = ArrayList<Long>(deltas.size)
        var prev = 0L
        for (d in deltas) {
            prev += d
            out.add(prev)
        }
        return out
    }

    private fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)
    private fun unzigzag(v: Long): Long = (v ushr 1) xor -(v and 1L)

    private fun writeVarint(sb: StringBuilder, value: Long) {
        var v = value
        while (v >= 0x20) {
            sb.append(Char(((v.toInt() and 0x1F) or 0x20) + 63))
            v = v ushr 5
        }
        sb.append(Char(v.toInt() + 63))
    }

    private fun readVarints(text: String): List<Long>? {
        val out = ArrayList<Long>()
        var cur = 0L
        var shift = 0
        for (ch in text) {
            val c = ch.code - 63
            if (c !in 0..63) return null
            if (shift > 60) return null
            cur = cur or ((c and 0x1F).toLong() shl shift)
            if (c and 0x20 != 0) {
                shift += 5
            } else {
                out.add(cur)
                cur = 0L
                shift = 0
            }
        }
        if (shift != 0) return null
        return out
    }
}
