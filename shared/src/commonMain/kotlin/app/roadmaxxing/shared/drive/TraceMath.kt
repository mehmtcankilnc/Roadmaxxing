package app.roadmaxxing.shared.drive

import app.roadmaxxing.shared.model.TracePoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_008.8

/** Great-circle distance in metres (haversine; plenty for GPS fixes seconds apart). */
internal fun distanceM(a: TracePoint, b: TracePoint): Double = distanceM(a.lat, a.lon, b.lat, b.lon)

internal fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = lat1 * PI / 180.0
    val p2 = lat2 * PI / 180.0
    val dp = p2 - p1
    val dl = (lon2 - lon1) * PI / 180.0
    val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Summary numbers of a trace, used for drive validation and the drive list. */
data class TraceStats(
    val pointCount: Int,
    val distanceM: Double,
    val durationMs: Long,
    /** Time spent at or above the moving threshold. */
    val movingDurationMs: Long,
    val maxSpeedMps: Double,
) {
    val avgMovingSpeedMps: Double get() = if (movingDurationMs <= 0) 0.0 else distanceM / (movingDurationMs / 1000.0)
    val avgSpeedMps: Double get() = if (durationMs <= 0) 0.0 else distanceM / (durationMs / 1000.0)

    companion object {
        fun of(points: List<TracePoint>, movingSpeedMps: Double = 2.5): TraceStats {
            if (points.isEmpty()) return TraceStats(0, 0.0, 0, 0, 0.0)
            var dist = 0.0
            var moving = 0L
            var maxSpeed = 0.0
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val d = distanceM(a, b)
                val dt = b.timeMs - a.timeMs
                dist += d
                val v = b.speedMps ?: if (dt > 0) d / (dt / 1000.0) else 0.0
                if (v >= movingSpeedMps) moving += dt
                if (v > maxSpeed) maxSpeed = v
            }
            return TraceStats(points.size, dist, points.last().timeMs - points.first().timeMs, moving, maxSpeed)
        }
    }
}
