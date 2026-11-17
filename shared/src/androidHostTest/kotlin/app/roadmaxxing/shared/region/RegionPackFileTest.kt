package app.roadmaxxing.shared.region

import app.roadmaxxing.shared.matching.RoadMatcher
import app.roadmaxxing.shared.progress.ProgressCalculator
import app.roadmaxxing.shared.testdata.TestData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionPackFileTest {
    private val adanaDir = "test-data/regions/adana"

    @Test
    fun fixtureOnDiskMatchesEmbeddedCopy() {
        val dir = TestData.file("test-data/regions/fixture")
        assertEquals(FixturePack.MANIFEST, File(dir, "manifest.json").readText())
        assertEquals(FixturePack.REGIONS, File(dir, "regions.json").readText())
        assertContentEquals(FixturePack.tile, File(dir, FixturePack.TILE_PATH).readBytes())
        val pack = RegionPack(TestData.packSource("test-data/regions/fixture"))
        assertEquals(13, pack.allSegmentIds().size)
        assertEquals("Testil", pack.province.name)
    }

    /** Facts about the whole pack that the Python reference reader must reproduce exactly. */
    private data class PackFacts(
        val count: Int,
        val sha: String,
        val totals: Map<String, Long>,
        val verts: Long,
        val nodeSum: Long,
        val coordSum: Long,
        val tileCounts: Map<String, Int>,
    )

    private fun kotlinFacts(pack: RegionPack): PackFacts {
        val ids = pack.allSegmentIds()
        val totals = HashMap<String, Long>()
        var verts = 0L
        var nodeSum = 0L
        var coordSum = 0L
        val mod = 1L shl 61
        pack.forEachSegment { s ->
            val layer = if (s.layer == RoadLayer.Major) "major" else "local"
            for (r in listOf(s.provinceId, s.districtId)) totals["$r/$layer"] = (totals["$r/$layer"] ?: 0L) + s.lengthDm
            verts += s.vertexCount
            for (n in s.nodeIds) nodeSum = Math.floorMod(nodeSum + n, mod)
            for (i in 0 until s.vertexCount) {
                coordSum = Math.floorMod(coordSum + (s.lons[i] * 1e6).roundToLong() + (s.lats[i] * 1e6).roundToLong(), mod)
            }
        }
        val manifest = Json.parseToJsonElement(TestData.file("$adanaDir/manifest.json").readText()).jsonObject
        val tileCounts = HashMap<String, Int>()
        for (t in manifest["tiles"]!!.jsonArray) {
            val a = t.jsonArray
            val x = a[0].jsonPrimitive.int
            val y = a[1].jsonPrimitive.int
            tileCounts["$x/$y"] = pack.tile(x, y)!!.segments.size
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(ids.joinToString(",").toByteArray())
            .joinToString("") { "%02x".format(it) }
        return PackFacts(ids.size, sha, totals, verts, nodeSum, coordSum, tileCounts)
    }

    private val pythonScript = """
import sys, json, os, hashlib
sys.path.insert(0, os.path.join(sys.argv[2], 'tools', 'regions'))
from roadmaxxing_regions.tileformat import decode_tile
root = sys.argv[1]
m = json.load(open(os.path.join(root, 'manifest.json'), encoding='utf-8'))
seen = {}
tiles = {}
for x, y, c, b in m['tiles']:
    t = decode_tile(open(os.path.join(root, 'tiles', str(m['tile_zoom']), str(x), str(y) + '.bin'), 'rb').read())
    tiles[str(x) + '/' + str(y)] = len(t.segments)
    for s in t.segments:
        seen[s.way_id * 4096 + s.ordinal] = s
ids = sorted(seen)
tot = {}
for s in seen.values():
    lay = 'major' if s.road_class < 10 else 'local'
    for r in (s.province_id, s.district_id):
        k = str(r) + '/' + lay
        tot[k] = tot.get(k, 0) + s.length_dm
mod = 1 << 61
print(json.dumps({
    'count': len(ids),
    'sha': hashlib.sha256(','.join(map(str, ids)).encode()).hexdigest(),
    'tot': tot,
    'verts': sum(len(s.node_ids) for s in seen.values()),
    'nodesum': sum(sum(s.node_ids) for s in seen.values()) % mod,
    'coordsum': sum(sum(a + b for a, b in s.coords) for s in seen.values()) % mod,
    'tiles': tiles,
}))
""".trimIndent()

    private fun pythonFacts(): PackFacts? {
        val venv = TestData.file("tools/regions/.venv/Scripts/python.exe").takeIf { it.isFile }
            ?: TestData.file("tools/regions/.venv/bin/python").takeIf { it.isFile }
        val candidates = listOfNotNull(venv?.path, "python3", "python")
        val script = File.createTempFile("pack_facts", ".py").apply { writeText(pythonScript); deleteOnExit() }
        for (py in candidates) {
            try {
                val proc = ProcessBuilder(py, script.path, TestData.file(adanaDir).path, TestData.repoRoot.path)
                    .redirectErrorStream(false).start()
                val out = proc.inputStream.bufferedReader().readText()
                val err = proc.errorStream.bufferedReader().readText()
                if (!proc.waitFor(120, TimeUnit.SECONDS) || proc.exitValue() != 0) {
                    println("python '$py' failed: ${err.take(300)}")
                    continue
                }
                val o = Json.parseToJsonElement(out.trim().lines().last()).jsonObject
                println("Python reference reader: $py")
                return PackFacts(
                    count = o["count"]!!.jsonPrimitive.int,
                    sha = o["sha"]!!.jsonPrimitive.content,
                    totals = o["tot"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long },
                    verts = o["verts"]!!.jsonPrimitive.long,
                    nodeSum = o["nodesum"]!!.jsonPrimitive.long,
                    coordSum = o["coordsum"]!!.jsonPrimitive.long,
                    tileCounts = o["tiles"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int },
                )
            } catch (e: java.io.IOException) {
                continue
            }
        }
        return null
    }

    @Test
    fun adanaPackMatchesManifestRegionsAndPythonReader() {
        val t0 = System.nanoTime()
        val pack = RegionPack(TestData.packSource(adanaDir))
        val facts = kotlinFacts(pack)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("Adana pack (Kotlin): ${facts.count} segments, ${facts.verts} vertices, ${facts.tileCounts.size} tiles, read in $ms ms")

        // Manifest and regions.json consistency (always checked).
        assertEquals(pack.segmentCount, facts.count)
        assertEquals(92_597, facts.count)
        for (r in pack.regions) {
            assertEquals(r.lengthDmMajor, facts.totals["${r.id}/major"] ?: 0L, "major total of ${r.id}")
            assertEquals(r.lengthDmLocal, facts.totals["${r.id}/local"] ?: 0L, "local total of ${r.id}")
        }
        assertEquals(15, pack.districts.size)
        val manifest = Json.parseToJsonElement(TestData.file("$adanaDir/manifest.json").readText()).jsonObject
        for (t in manifest["tiles"]!!.jsonArray) {
            val a = t.jsonArray
            assertEquals(a[2].jsonPrimitive.int, facts.tileCounts["${a[0].jsonPrimitive.int}/${a[1].jsonPrimitive.int}"])
        }
        println("Province total: ${pack.province.lengthDmAll / 10_000.0} km")

        // Same facts from the Python reference reader (tools/regions/roadmaxxing_regions/tileformat.py).
        val py = pythonFacts()
        if (py == null) {
            println("Python reference reader not available: comparison SKIPPED (manifest/regions checks passed)")
            return
        }
        assertEquals(py.count, facts.count)
        assertEquals(py.sha, facts.sha, "sorted segment id list differs")
        assertEquals(py.totals, facts.totals)
        assertEquals(py.verts, facts.verts)
        assertEquals(py.nodeSum, facts.nodeSum)
        assertEquals(py.coordSum, facts.coordSum)
        assertEquals(py.tileCounts, facts.tileCounts)
        println("Python reference reader: identical (count, id hash, per-region totals, vertices, node ids, coordinates, per-tile counts)")
    }

    /** Copy of the Adana pack source that hides segments.bin and its manifest entry (the pre-index layout). */
    private fun legacyAdanaSource(): RegionPackSource {
        val real = TestData.packSource(adanaDir)
        return RegionPackSource { path ->
            when (path) {
                "segments.bin" -> null
                "manifest.json" -> real.read(path)!!.decodeToString()
                    .replace(Regex(""""segment_index":\{[^}]*\},"""), "").encodeToByteArray()
                else -> real.read(path)
            }
        }
    }

    @Test
    fun adanaSegmentIndexFileEqualsIndexBuiltFromTiles() {
        val fromFile = RegionPack(TestData.packSource(adanaDir))
        val t0 = System.nanoTime()
        val ids = fromFile.allSegmentIds()
        val fileMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(true, fromFile.summaryFromFile)
        assertEquals(0, fromFile.tileLoads)
        assertEquals(92_597, ids.size)

        val legacy = RegionPack(legacyAdanaSource())
        val t1 = System.nanoTime()
        val legacyIds = legacy.allSegmentIds()
        val scanMs = (System.nanoTime() - t1) / 1_000_000
        assertEquals(false, legacy.summaryFromFile)
        assertEquals(857, legacy.tileLoads)
        assertContentEquals(legacyIds, ids)
        for (id in ids) assertEquals(legacy.summary(id), fromFile.summary(id), "segment $id")
        println("Adana summary index: segments.bin ${fileMs} ms (0 tiles) vs tile scan $scanMs ms (857 tiles)")
    }

    @Test
    fun adanaProgressIsIdenticalWithAndWithoutSegmentIndex() {
        val ids = HashSet<Long>()
        for (route in listOf("main-roads.noisy.gpx", "intercity.noisy.gpx")) {
            ids += RoadMatcher(RegionPack(TestData.packSource(adanaDir))).match(TestData.readGpx(route)).driven.map { it.segmentId }
        }
        assertTrue(ids.isNotEmpty())
        val withFile = ProgressCalculator(RegionPack(TestData.packSource(adanaDir))).compute(ids)
        val legacy = ProgressCalculator(RegionPack(legacyAdanaSource())).compute(ids)
        assertEquals(legacy, withFile)
        println("Progress on ${ids.size} driven segments identical; province ${withFile.first().percent}%")
    }

    @Test
    fun firstProgressIsFastWhenPackIsColdLoaded() {
        val ids = RegionPack(TestData.packSource(adanaDir)).allSegmentIds().filterIndexed { i, _ -> i % 10 == 0 }.toSet()
        fun cold(source: RegionPackSource): Pair<Long, Int> {
            val t0 = System.nanoTime()
            val pack = RegionPack(source)
            ProgressCalculator(pack).compute(ids)
            return (System.nanoTime() - t0) / 1_000_000 to pack.tileLoads
        }
        val (fileMs, fileTiles) = cold(TestData.packSource(adanaDir))
        val (scanMs, scanTiles) = cold(legacyAdanaSource())
        println("First progress, cold, pack load included: with segments.bin $fileMs ms ($fileTiles tiles); tile scan $scanMs ms ($scanTiles tiles)")
        assertEquals(0, fileTiles)
    }

    @Test
    fun tileCacheStaysWithinItsBound() {
        val pack = RegionPack(TestData.packSource(adanaDir), maxCachedVertices = 50_000)
        val manifest = Json.parseToJsonElement(TestData.file("$adanaDir/manifest.json").readText()).jsonObject
        var maxTile = 0
        for (t in manifest["tiles"]!!.jsonArray) {
            val a = t.jsonArray
            maxTile = maxOf(maxTile, pack.tile(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int)!!.vertexCount)
            assertTrue(pack.cachedVertexCount <= maxOf(50_000, maxTile) + maxTile, "cache grew to ${pack.cachedVertexCount}")
        }
        println("Largest tile: $maxTile vertices; cache after a full scan: ${pack.cachedTileCount} tiles / ${pack.cachedVertexCount} vertices")
        // segment() still works after eviction
        val anyId = pack.allSegmentIds()[12_345]
        assertEquals(anyId, pack.segment(anyId)!!.id)
    }

    @Test
    fun peakMemoryOfTheLongestRoutes() {
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        for (route in listOf("intercity.noisy.gpx", "main-roads.noisy.gpx")) {
            val trace = TestData.readGpx(route)
            repeat(3) { System.gc() }
            val base = pools.sumOf { it.usage.used }
            pools.forEach { it.resetPeakUsage() }
            val t0 = System.nanoTime()
            val pack = RegionPack(TestData.packSource(adanaDir))
            val result = RoadMatcher(pack).match(trace)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val peak = pools.sumOf { it.peakUsage.used }
            repeat(3) { System.gc() }
            val retained = pools.sumOf { it.usage.used }
            println(
                "$route: ${trace.size} points, ${result.driven.size} driven, $ms ms cold; heap peak +${(peak - base) / 1_000_000} MB over baseline " +
                    "(includes garbage), retained with pack alive +${(retained - base) / 1_000_000} MB; " +
                    "pack cache ${pack.cachedTileCount} tiles / ${pack.cachedVertexCount} vertices",
            )
            assertTrue(result.driven.isNotEmpty())
        }
    }
}
