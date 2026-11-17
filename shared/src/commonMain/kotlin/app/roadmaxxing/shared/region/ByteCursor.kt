package app.roadmaxxing.shared.region

/** Sequential reader of the tile encoding primitives (FORMAT.md §5.2). */
internal class ByteCursor(private val bytes: ByteArray, var pos: Int = 0) {
    val remaining: Int get() = bytes.size - pos

    fun u8(): Int {
        if (pos >= bytes.size) throw RegionPackFormatException("unexpected end of tile at byte $pos")
        return bytes[pos++].toInt() and 0xFF
    }

    fun uvarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            if (shift == 63 && (b and 0x7E) != 0) throw RegionPackFormatException("varint overflows 64 bits")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
            if (shift > 63) throw RegionPackFormatException("varint too long")
        }
    }

    fun svarint(): Long = unzigzag(uvarint())

    fun uvarintInt(what: String): Int {
        val v = uvarint()
        if (v < 0 || v > Int.MAX_VALUE) throw RegionPackFormatException("$what out of range: $v")
        return v.toInt()
    }

    companion object {
        fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)
        fun unzigzag(z: Long): Long = (z ushr 1) xor -(z and 1)
    }
}
