package app.roadmaxxing.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietHoursTest {
    private fun q(start: Int, end: Int) = QuietHours(true, start, end)

    @Test
    fun sameDayRangeStartInclusiveEndExclusive() {
        val r = q(9 * 60, 17 * 60)
        assertFalse(r.contains(9 * 60 - 1))
        assertTrue(r.contains(9 * 60))
        assertTrue(r.contains(12 * 60))
        assertTrue(r.contains(17 * 60 - 1))
        assertFalse(r.contains(17 * 60))
    }

    @Test
    fun rangeCrossingMidnight() {
        val r = QuietHours.Default.copy(enabled = true)
        assertTrue(r.contains(22 * 60))
        assertTrue(r.contains(23 * 60 + 59))
        assertTrue(r.contains(0))
        assertTrue(r.contains(6 * 60 + 59))
        assertFalse(r.contains(7 * 60))
        assertFalse(r.contains(12 * 60))
        assertFalse(r.contains(22 * 60 - 1))
    }

    @Test
    fun equalStartAndEndIsEmpty() {
        val r = q(600, 600)
        for (m in listOf(0, 599, 600, 601, 1439)) assertFalse(r.contains(m))
    }

    @Test
    fun defaultsAreOffAnd22To07() {
        assertEquals(QuietHours(false, 1320, 420), QuietHours.Default)
    }
}
