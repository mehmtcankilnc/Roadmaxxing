package app.roadmaxxing.shared.model

/**
 * One GPS fix of a drive.
 *
 * @param timeMs epoch milliseconds
 * @param speedMps speed in m/s, null when the platform did not report it
 * @param bearingDeg course in degrees clockwise from true north, null when unknown
 *   (the Android emulator never reports it; the matcher derives it from consecutive points)
 * @param accuracyM horizontal accuracy radius in metres, null when unknown
 */
data class TracePoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val accuracyM: Double?,
)
