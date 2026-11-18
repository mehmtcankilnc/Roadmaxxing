package app.roadmaxxing.shared.onboarding

import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.testdata.TestData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IntroRoadsProviderTest {
    private fun provider() = IntroRoadsProvider(RegionPackProvider(TestData.packSource("test-data/regions/adana")))

    @Test
    fun loadsBoundedSampleWithinBounds() = runBlocking {
        val p = provider()
        val t = System.nanoTime()
        val roads = p.load()
        val ms = (System.nanoTime() - t) / 1_000_000
        val points = roads.roads.sumOf { it.size }
        println("PERF intro roads cold load: ${ms}ms polylines=${roads.roads.size} points=$points")
        assertTrue(roads.roads.isNotEmpty())
        assertTrue(roads.roads.size <= IntroRoadsProvider.MAX_POLYLINES)
        assertTrue(points in 1..5_000, "points=$points")
        assertTrue(roads.roads.all { it.size >= 2 })
        for (r in roads.roads) for (pt in r) {
            assertTrue(pt.lat in roads.south..roads.north && pt.lon in roads.west..roads.east)
        }
        assertTrue(roads.south < roads.north && roads.west < roads.east)
    }

    @Test
    fun secondCallReturnsCachedInstance() = runBlocking {
        val p = provider()
        val first = p.load()
        val t = System.nanoTime()
        val second = p.load()
        val ms = (System.nanoTime() - t) / 1_000_000
        println("PERF intro roads cached load: ${ms}ms")
        assertSame(first, second)
    }
}
