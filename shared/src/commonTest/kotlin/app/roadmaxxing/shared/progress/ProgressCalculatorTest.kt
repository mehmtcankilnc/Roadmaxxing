package app.roadmaxxing.shared.progress

import app.roadmaxxing.shared.region.FixturePack
import app.roadmaxxing.shared.region.FixturePack.id
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressCalculatorTest {
    private val calc = ProgressCalculator(FixturePack.pack())

    @Test
    fun emptySetIsZeroForEveryRegion() {
        val p = calc.compute(emptySet())
        assertEquals(listOf(1L, 11L, 12L), p.map { it.regionId })
        p.forEach {
            assertEquals(0.0, it.percent)
            assertEquals(0L, it.drivenDm)
        }
        assertEquals(27234L, p[0].totalDmMajor)
        assertEquals(34445L, p[0].totalDmLocal)
    }

    @Test
    fun layersAndDistrictsAreSeparated() {
        // 1001:0 primary West 3560, 1001:2 primary East 3560, 2004:0 living_street East 3329.
        val p = calc.compute(setOf(id(1001, 0), id(1001, 2), id(2004, 0))).associateBy { it.regionId }
        assertEquals(3560L, p.getValue(11).drivenDmMajor)
        assertEquals(0L, p.getValue(11).drivenDmLocal)
        assertEquals(3560L, p.getValue(12).drivenDmMajor)
        assertEquals(3329L, p.getValue(12).drivenDmLocal)
        assertEquals(7120L, p.getValue(1).drivenDmMajor)
        assertEquals(3329L, p.getValue(1).drivenDmLocal)
        assertEquals(3560.0 / 17894 * 100, p.getValue(11).percentMajor, 1e-9)
        assertEquals((3560.0 + 3329) / 19558 * 100, p.getValue(12).percent, 1e-9)
        assertEquals(3329.0 / 34445 * 100, p.getValue(1).percentLocal, 1e-9)
    }

    @Test
    fun provinceEqualsSumOfDistricts() {
        val all = FixturePack.pack().allSegmentIds().toSet()
        val subset = all.filterIndexed { i, _ -> i % 2 == 0 }.toSet()
        for (set in listOf(all, subset)) {
            val p = calc.compute(set)
            val province = p.first()
            val districts = p.drop(1)
            assertEquals(province.drivenDmMajor, districts.sumOf { it.drivenDmMajor })
            assertEquals(province.drivenDmLocal, districts.sumOf { it.drivenDmLocal })
        }
        val full = calc.compute(all)
        full.forEach { assertEquals(100.0, it.percent, 1e-9) }
    }

    @Test
    fun repeatedAndUnknownIdsDoNotChangeTheResult() {
        val ids = listOf(id(1001, 1), id(2005, 0))
        val once = calc.compute(ids.toSet())
        val twice = calc.compute((ids + ids).toSet())
        val withUnknown = calc.compute(ids.toSet() + id(999_999, 0) + id(1005, 0))
        assertEquals(once, twice)
        assertEquals(once, withUnknown)
    }

    @Test
    fun zeroTotalsGiveZeroPercent() {
        val p = RegionProgress(1, 0, 0, 0, 0)
        assertEquals(0.0, p.percent)
        assertEquals(0.0, p.percentMajor)
    }
}
