package app.roadmaxxing.ui.main

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Fixed-decimal number with the locale's decimal separator (from string resources). */
internal fun formatDecimal(value: Double, decimals: Int, separator: String): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = (abs(value) * factor).roundToLong()
    val whole = scaled / factor
    val frac = (scaled % factor).toString().padStart(decimals, '0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole$separator$frac"
}

/** "16.11 14:05" in the device time zone; numeric, so it reads the same in Turkish and English. */
@OptIn(ExperimentalTime::class)
internal fun formatDateTime(epochMs: Long): String {
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${two(t.day)}.${two(t.month.ordinal + 1)} ${two(t.hour)}:${two(t.minute)}"
}
