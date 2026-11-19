package app.roadmaxxing.shared.units

import app.roadmaxxing.shared.settings.DistanceUnit
import app.roadmaxxing.shared.settings.QuietHours
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Exact international mile. */
const val METERS_PER_MILE: Double = 1609.344

/** Metres per second to kilometres per hour. */
const val KMH_PER_MPS: Double = 3.6

/** Metres per second to miles per hour (3600 / 1609.344). */
const val MPH_PER_MPS: Double = 3600.0 / METERS_PER_MILE

/** A distance in metres expressed in [unit]. */
fun metersIn(meters: Double, unit: DistanceUnit): Double = when (unit) {
    DistanceUnit.Kilometers -> meters / 1000.0
    DistanceUnit.Miles -> meters / METERS_PER_MILE
}

/** A speed in metres per second expressed in km/h or mph depending on [unit]. */
fun speedIn(metersPerSecond: Double, unit: DistanceUnit): Double = when (unit) {
    DistanceUnit.Kilometers -> metersPerSecond * KMH_PER_MPS
    DistanceUnit.Miles -> metersPerSecond * MPH_PER_MPS
}

/** True when the end-of-drive notification must stay silent at [epochMs] in [zone]. */
@OptIn(ExperimentalTime::class)
fun isQuietAt(quiet: QuietHours, epochMs: Long, zone: TimeZone): Boolean {
    if (!quiet.enabled) return false
    val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    return quiet.contains(t.hour * 60 + t.minute)
}
