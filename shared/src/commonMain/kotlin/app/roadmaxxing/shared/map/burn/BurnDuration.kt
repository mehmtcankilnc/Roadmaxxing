package app.roadmaxxing.shared.map.burn

import kotlin.math.pow

/**
 * Single source of the burn animation length, used by BOTH the map burn ([BurnTimeline.durationMs]) and the
 * summary-screen route animation.
 *
 * `clamp(600 + 1400 * (km / 10)^(2/3), 600, 2000)` ms, where `km` is the fresh (newly driven) length: a 0 m, negative
 * or NaN length gives 600 ms, 10 km and more give 2000 ms.
 */
fun burnDurationMs(lengthMeters: Double): Long {
    if (lengthMeters.isNaN() || lengthMeters <= 0.0) return BURN_MIN_DURATION_MS
    val km = lengthMeters / 1000.0
    val ms = BURN_MIN_DURATION_MS + (BURN_MAX_DURATION_MS - BURN_MIN_DURATION_MS) * (km / 10.0).pow(2.0 / 3.0)
    return ms.toLong().coerceIn(BURN_MIN_DURATION_MS, BURN_MAX_DURATION_MS)
}

private const val BURN_MIN_DURATION_MS: Long = 600
private const val BURN_MAX_DURATION_MS: Long = 2000
