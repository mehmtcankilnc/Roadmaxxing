package app.roadmaxxing.shared.region

/**
 * test-data/regions/fixture embedded byte for byte (no file system, so it also runs on iOS).
 * Map and segment table: test-data/regions/fixture/README.md.
 */
object FixturePack {
    const val MANIFEST: String = """{"coord_decimals":6,"format":"roadmaxxing-region-pack","format_version":1,"pack_id":"fixture","province_id":1,"segment_count":13,"segment_index":{"bytes":102,"path":"segments.bin"},"source":{"file":"fixture.osm","md5":"d15c29759109ba7fb44b88c500a13aab","osm_replication_timestamp":"","url":null},"stats":{"district_gap_assigned":1,"district_overlap":0,"drivable_ways_read":11,"segments_unresolved":0},"tile_columns":["x","y","segment_count","bytes"],"tile_path":"tiles/{z}/{x}/{y}.bin","tile_zoom":13,"tiles":[[4892,3188,13,309]]}
"""

    const val REGIONS: String = """{"format":"roadmaxxing-regions","format_version":1,"regions":[{"bbox":[35.0,37.0,35.02,37.01],"boundary":[[[[35.02,37.0],[35.0,37.0],[35.0,37.01],[35.02,37.01],[35.02,37.0]]]],"id":1,"iso3166_2":"TR-99","length_dm":{"all":61679,"local":34445,"major":27234},"level":1,"name":"Testil","name_en":"Testprovince","name_tr":"Testil","osm_admin_level":4,"parent_id":null,"segment_count":{"all":13,"local":8,"major":5}},{"bbox":[35.0,37.0,35.01,37.01],"boundary":[[[[35.01,37.0],[35.0,37.0],[35.0,37.01],[35.01,37.01],[35.01,37.0]]]],"id":11,"iso3166_2":null,"length_dm":{"all":42121,"local":24227,"major":17894},"level":2,"name":"Batı","name_en":"West","name_tr":"Batı","osm_admin_level":6,"parent_id":1,"segment_count":{"all":8,"local":5,"major":3}},{"bbox":[35.01,37.0,35.02,37.01],"boundary":[[[[35.02,37.0],[35.01,37.0],[35.01,37.01],[35.02,37.01],[35.02,37.0]]]],"id":12,"iso3166_2":null,"length_dm":{"all":19558,"local":10218,"major":9340},"level":2,"name":"Doğu","name_en":"East","name_tr":"Doğu","osm_admin_level":6,"parent_id":1,"segment_count":{"all":5,"local":3,"major":2}}]}
"""

    const val TILE_PATH: String = "tiles/13/4892/3188.bin"

    private const val TILE_HEX: String =
        "524d5854010d9c26f4180603010b0c0de90700040001e81b026f02a0dab021909ba523c03e000001040001d03703700202e098b121909ba523c03e00c03e" +
            "000002040002e81b027202e095b221909ba523c03e0001000a0001e81b026502a0dab021a0eca423c03e0001002a0002e81b027b02e095b22180caa523c0" +
            "3e000300080001ae3802703ae098b121909ba52300c8650100280002942d05970102020205b0a5b221b8e4a423a01f0000d00f9f1f0000cf0fe207000a00" +
            "01811a026514a0dab021a0eca42300f02e00010a0001811a026f14a0dab021909ba52300f02e01000a0001811a026614e098b121a0eca42300f02e01004a" +
            "0002811a027212e095b221909ba52300f02e01000c0002811a027312a0d4b221909ba52300f02e01000b0001b853027904a0dab02180caa523c0bb0100"

    /** segments.bin of the fixture (FORMAT.md section 6). */
    private const val SEGMENTS_HEX: String =
        "524d58530103010b0c0d80a0fa01e81b04000101d03704000101e81b040002fe1fe81b0a00018020e81b0a00028060ae380800018020942d08000280c0f801811a0a000101811a0a0001ff1f811a0a00018020811a0a00028020811a0c00028020b8530b0001"

    val tile: ByteArray get() = hex(TILE_HEX)
    val segments: ByteArray get() = hex(SEGMENTS_HEX)

    /** The same pack as written before `segments.bin` existed: no manifest field, no file. */
    val manifestWithoutSegmentIndex: String
        get() = MANIFEST.replace(""""segment_index":{"bytes":102,"path":"segments.bin"},""", "")

    fun source(
        manifest: String = MANIFEST,
        regions: String = REGIONS,
        tile: ByteArray = this.tile,
        segments: ByteArray? = this.segments,
    ): RegionPackSource = RegionPackSource { path ->
        when (path) {
            "segments.bin" -> segments
            "manifest.json" -> manifest.encodeToByteArray()
            "regions.json" -> regions.encodeToByteArray()
            TILE_PATH -> tile
            else -> null
        }
    }

    fun pack(): RegionPack = RegionPack(source())

    fun id(way: Long, ordinal: Int): Long = Segment.idOf(way, ordinal)

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
