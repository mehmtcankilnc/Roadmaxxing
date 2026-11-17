package app.roadmaxxing.shared.region

/** Reads a file of the pack by its relative path ("manifest.json", "regions.json", "tiles/13/4892/3188.bin"); null if absent. */
fun interface RegionPackSource {
    fun read(path: String): ByteArray?
}
