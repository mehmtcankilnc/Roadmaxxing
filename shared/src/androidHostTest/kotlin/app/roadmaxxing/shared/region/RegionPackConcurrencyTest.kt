package app.roadmaxxing.shared.region

import app.roadmaxxing.shared.matching.RoadMatcher
import app.roadmaxxing.shared.testdata.TestData
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hammers one cold [RegionPack] from many threads; results must equal the single-threaded ones. */
class RegionPackConcurrencyTest {
    private val source = TestData.packSource("test-data/regions/adana")

    private fun fingerprint(s: Segment?): String =
        if (s == null) "null" else "${s.id}:${s.lengthDm}:${s.roadClass}:${s.vertexCount}:${s.lats.sum()}:${s.lons.sum()}"

    @Test
    fun parallelReadsMatchSingleThreadedResults() {
        val trace = TestData.readGpx("main-roads.gpx")
        val reference = RegionPack(source)
        val ids = reference.allSegmentIds().toList().shuffled(Random(7)).take(1500)
        val expectedSegments = ids.map { fingerprint(reference.segment(it)) }
        val expectedSummaries = ids.map { reference.summary(it) }
        val expectedMatch = RoadMatcher(reference).match(trace)

        // Small cache so eviction races happen too.
        val shared = RegionPack(source, maxCachedVertices = 60_000)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val done = CountDownLatch(threads)
        repeat(threads) { n ->
            pool.execute {
                try {
                    start.await()
                    val order = ids.indices.toList().shuffled(Random(n))
                    for ((k, i) in order.withIndex()) {
                        assertEquals(expectedSegments[i], fingerprint(shared.segment(ids[i])))
                        assertEquals(expectedSummaries[i], shared.summary(ids[i]))
                        if (n % 4 == 0 && k == 300) {
                            assertEquals(expectedMatch, RoadMatcher(shared).match(trace))
                        }
                    }
                } catch (t: Throwable) {
                    errors += t
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.MINUTES), "threads did not finish")
        pool.shutdownNow()
        assertTrue(errors.isEmpty(), "errors: ${errors.firstOrNull()}")
        assertTrue(shared.cachedVertexCount <= 60_000 + 60_000, "cache bound")
    }
}
