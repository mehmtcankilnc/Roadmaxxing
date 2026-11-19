package app.roadmaxxing.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.unit_km
import app.roadmaxxing.resources.unit_kmh
import app.roadmaxxing.resources.unit_mi
import app.roadmaxxing.resources.unit_mph
import app.roadmaxxing.shared.settings.DistanceUnit
import app.roadmaxxing.shared.settings.SettingsRepository
import app.roadmaxxing.shared.units.metersIn
import app.roadmaxxing.shared.units.speedIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * The distance unit the user picked, collected so open screens update when it changes. Resolved through Koin
 * directly (not a composition-local) so it also works inside the off-screen card renderer.
 */
@Composable
internal fun rememberDistanceUnit(): DistanceUnit {
    val flow = remember { KoinPlatform.getKoin().get<SettingsRepository>().distanceUnit }
    val unit by flow.collectAsState()
    return unit
}

internal fun distanceUnitRes(unit: DistanceUnit): StringResource =
    if (unit == DistanceUnit.Miles) Res.string.unit_mi else Res.string.unit_km

internal fun speedUnitRes(unit: DistanceUnit): StringResource =
    if (unit == DistanceUnit.Miles) Res.string.unit_mph else Res.string.unit_kmh

@Composable
internal fun distanceUnitLabel(unit: DistanceUnit): String = stringResource(distanceUnitRes(unit))

@Composable
internal fun speedUnitLabel(unit: DistanceUnit): String = stringResource(speedUnitRes(unit))

/** [meters] in [unit] as a locale-formatted number (no unit label). */
internal fun formatDistanceValue(meters: Double, unit: DistanceUnit, decimals: Int, separator: String): String =
    formatDecimal(metersIn(meters, unit), decimals, separator)

/** [metersPerSecond] in km/h or mph as a locale-formatted number (no unit label). */
internal fun formatSpeedValue(metersPerSecond: Double, unit: DistanceUnit, decimals: Int, separator: String): String =
    formatDecimal(speedIn(metersPerSecond, unit), decimals, separator)

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
