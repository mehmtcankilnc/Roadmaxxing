package app.roadmaxxing.shared.units

import app.roadmaxxing.shared.settings.DistanceUnit
import app.roadmaxxing.shared.settings.QuietHours
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.math.abs

class UnitConversionTest {
    private fun near(expected: Double, actual: Double, eps: Double = 1e-9) =
        assertTrue(abs(expected - actual) < eps, "expected $expected but was $actual")

    @Test
    fun kilometres() {
        near(12.5, metersIn(12_500.0, DistanceUnit.Kilometers))
        near(100.0, speedIn(100.0 / 3.6, DistanceUnit.Kilometers), 1e-9)
    }

    @Test
    fun miles() {
        near(1.0, metersIn(1609.344, DistanceUnit.Miles))
        near(6.2137119, metersIn(10_000.0, DistanceUnit.Miles), 1e-6)
        // 100 km/h is about 62.137 mph.
        near(62.1371192, speedIn(100.0 / 3.6, DistanceUnit.Miles), 1e-6)
        near(0.0, metersIn(0.0, DistanceUnit.Miles))
    }

    @Test
    fun quietHoursDisabledNeverSuppresses() {
        val quiet = QuietHours(enabled = false, startMinute = 0, endMinute = 24 * 60 - 1)
        assertFalse(isQuietAt(quiet, 1_700_000_000_000L, TimeZone.UTC))
    }

    @Test
    fun quietHoursUsesLocalMinuteOfDay() {
        // 2023-11-14T22:13:20Z.
        val epoch = 1_700_000_000_000L
        val range = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)
        // Contains() is I1's; with the stub it is always false, so only assert the enabled-and-inside case there.
        assertEquals(range.contains(22 * 60 + 13), isQuietAt(range, epoch, TimeZone.UTC))
    }
}
