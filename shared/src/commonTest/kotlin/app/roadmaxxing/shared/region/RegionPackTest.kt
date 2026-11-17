package app.roadmaxxing.shared.region

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ByteCursorTest {
    @Test
    fun uvarintDecodesLeb128() {
        assertEquals(0L, ByteCursor(byteArrayOf(0)).uvarint())
        assertEquals(127L, ByteCursor(byteArrayOf(0x7F)).uvarint())
        assertEquals(300L, ByteCursor(byteArrayOf(0xAC.toByte(), 0x02)).uvarint())
        // 13 * 4096-ish way id from the fixture tile: 0xe9 0x07 = 1001
        assertEquals(1001L, ByteCursor(byteArrayOf(0xE9.toByte(), 0x07)).uvarint())
        val max = ByteArray(10) { 0xFF.toByte() }.also { it[9] = 0x01 }
        assertEquals(-1L, ByteCursor(max).uvarint()) // 2^64 - 1 as unsigned
    }

    @Test
    fun svarintIsZigzag() {
        for (v in listOf(0L, 1L, -1L, 2L, -2L, 123_456_789L, -987_654_321L, Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertEquals(v, ByteCursor.unzigzag(ByteCursor.zigzag(v)))
        }
        assertEquals(1L, ByteCursor.zigzag(-1L))
        assertEquals(2L, ByteCursor.zigzag(1L))
        assertEquals(-1L, ByteCursor(byteArrayOf(0x01)).svarint())
        assertEquals(1L, ByteCursor(byteArrayOf(0x02)).svarint())
    }

    @Test
    fun malformedVarintsFail() {
        assertFailsWith<RegionPackFormatException> { ByteCursor(byteArrayOf(0x80.toByte())).uvarint() }
        assertFailsWith<RegionPackFormatException> { ByteCursor(ByteArray(11) { 0xFF.toByte() }).uvarint() }
    }
}

class TileDecoderTest {
    @Test
    fun decodesFixtureTile() {
        val t = TileDecoder.decode(FixturePack.tile)
        assertEquals(13, t.zoom)
        assertEquals(4892, t.x)
        assertEquals(3188, t.y)
        assertEquals(6, t.coordDecimals)
        assertContentEquals(longArrayOf(1, 11, 12), t.regionIds)
        assertEquals(13, t.segments.size)
        assertEquals(t.segments.map { it.id }.sorted(), t.segments.map { it.id }) // sorted by (way, ordinal)

        val s = t.segments.first { it.id == FixturePack.id(1001, 1) }
        assertContentEquals(longArrayOf(112, 113, 114), s.nodeIds)
        assertEquals(7120L, s.lengthDm)
        assertEquals(4, s.roadClass)
        assertEquals(RoadLayer.Major, s.layer)
        assertEquals(Oneway.Both, s.oneway)
        assertEquals(1L, s.provinceId)
        assertEquals(11L, s.districtId)
        assertEquals(35.006, s.lons[0], 1e-9)
        assertEquals(37.005, s.lats[0], 1e-9)
        assertEquals(35.010, s.lons[1], 1e-9)
        assertEquals(35.014, s.lons[2], 1e-9)

        assertEquals(Oneway.Forward, t.segments.first { it.id == FixturePack.id(1003, 0) }.oneway)
        assertEquals(Oneway.Backward, t.segments.first { it.id == FixturePack.id(2003, 0) }.oneway)
        val roundabout = t.segments.first { it.id == FixturePack.id(1007, 0) }
        assertEquals(roundabout.nodeIds.first(), roundabout.nodeIds.last())
        assertEquals(Oneway.Forward, roundabout.oneway)
        val living = t.segments.first { it.id == FixturePack.id(2004, 0) }
        assertEquals(12, living.roadClass)
        assertEquals(RoadLayer.Local, living.layer)
        assertEquals(12L, living.districtId)
    }

    @Test
    fun rejectsUnknownVersionAndCorruption() {
        val bad = FixturePack.tile.copyOf().also { it[4] = 2 }
        assertFailsWith<RegionPackFormatException> { TileDecoder.decode(bad) }
        val magic = FixturePack.tile.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFailsWith<RegionPackFormatException> { TileDecoder.decode(magic) }
        assertFailsWith<RegionPackFormatException> { TileDecoder.decode(FixturePack.tile + byteArrayOf(0)) }
        val tile = FixturePack.tile
        assertFailsWith<RegionPackFormatException> { TileDecoder.decode(tile.copyOf(tile.size - 1)) }
    }
}

class RegionPackTest {
    @Test
    fun readsManifestAndRegions() {
        val pack = FixturePack.pack()
        assertEquals("fixture", pack.packId)
        assertEquals(1L, pack.provinceId)
        assertEquals(13, pack.tileZoom)
        assertEquals(13, pack.segmentCount)
        assertEquals(listOf(1L, 11L, 12L), pack.regions.map { it.id })
        val p = pack.province
        assertEquals("Testil", p.name)
        assertEquals("Testprovince", p.nameEn)
        assertNull(p.parentId)
        assertEquals(27234L, p.lengthDmMajor)
        assertEquals(34445L, p.lengthDmLocal)
        val west = pack.region(11)!!
        assertEquals("Batı", west.nameTr)
        assertEquals(1L, west.parentId)
        assertEquals(2, west.level)
        assertEquals(p.lengthDmMajor, pack.districts.sumOf { it.lengthDmMajor })
        assertEquals(p.lengthDmLocal, pack.districts.sumOf { it.lengthDmLocal })
    }

    @Test
    fun segmentLookupAndTotalsMatchRegionsJson() {
        val pack = FixturePack.pack()
        assertEquals(13, pack.allSegmentIds().size)
        assertNotNull(pack.segment(FixturePack.id(2005, 0)))
        assertNull(pack.segment(FixturePack.id(1005, 0))) // outside the province
        assertNull(pack.segment(FixturePack.id(3001, 0))) // service road, not counted
        val totals = HashMap<Pair<Long, RoadLayer>, Long>()
        pack.forEachSegment { s ->
            for (r in listOf(s.provinceId, s.districtId)) {
                totals[r to s.layer] = (totals[r to s.layer] ?: 0L) + s.lengthDm
            }
        }
        for (r in pack.regions) {
            assertEquals(r.lengthDmMajor, totals[r.id to RoadLayer.Major] ?: 0L, "major ${r.id}")
            assertEquals(r.lengthDmLocal, totals[r.id to RoadLayer.Local] ?: 0L, "local ${r.id}")
        }
        assertEquals(7120L, pack.summary(FixturePack.id(1001, 1))!!.lengthDm)
    }

    @Test
    fun tilesAreLoadedLazilyAndCached() {
        val pack = FixturePack.pack()
        assertEquals(0, pack.tileLoads)
        pack.segmentsInBox(37.004, 35.001, 37.006, 35.003)
        assertEquals(1, pack.tileLoads)
        pack.segmentsInBox(37.004, 35.001, 37.006, 35.003)
        pack.segment(FixturePack.id(1001, 0))
        assertEquals(1, pack.tileLoads)
        assertTrue(pack.segmentsInBox(10.0, 10.0, 10.1, 10.1).isEmpty())
    }

    @Test
    fun rejectsUnknownFormatVersions() {
        val v2 = FixturePack.MANIFEST.replace("\"format_version\":1", "\"format_version\":2")
        assertFailsWith<RegionPackFormatException> { RegionPack(FixturePack.source(manifest = v2)) }
        val r2 = FixturePack.REGIONS.replace("\"format_version\":1", "\"format_version\":2")
        assertFailsWith<RegionPackFormatException> { RegionPack(FixturePack.source(regions = r2)) }
        val wrong = FixturePack.MANIFEST.replace("roadmaxxing-region-pack", "something-else")
        assertFailsWith<RegionPackFormatException> { RegionPack(FixturePack.source(manifest = wrong)) }
        assertFailsWith<RegionPackFormatException> { RegionPack(RegionPackSource { null }) }
        val badTile = FixturePack.tile.copyOf().also { it[4] = 9 }
        val pack = RegionPack(FixturePack.source(tile = badTile))
        assertFailsWith<RegionPackFormatException> { pack.tile(4892, 3188) }
    }

    @Test
    fun ignoresUnknownJsonKeys() {
        val extra = FixturePack.MANIFEST.replace("{\"coord_decimals\"", "{\"future_key\":{\"a\":[1,2]},\"coord_decimals\"")
        assertEquals("fixture", RegionPack(FixturePack.source(manifest = extra)).packId)
    }
}
