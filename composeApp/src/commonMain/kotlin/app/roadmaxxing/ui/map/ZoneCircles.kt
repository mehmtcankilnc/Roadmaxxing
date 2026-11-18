package app.roadmaxxing.ui.map

import app.roadmaxxing.shared.privacy.PrivacyZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val CIRCLE_POINTS = 64
private const val METERS_PER_DEGREE_LAT = 111_320.0
private const val MAX_LAT_FOR_SCALE = 85.0

/**
 * GeoJSON FeatureCollection with one Polygon per privacy zone, each circle approximated with [CIRCLE_POINTS]
 * points. Plain equirectangular offsets are accurate enough at zone sizes (hundreds of metres).
 */
internal fun zoneCirclesGeoJson(zones: List<PrivacyZone>): String {
    if (zones.isEmpty()) return EMPTY_FEATURES
    return buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        zones.forEachIndexed { index, zone ->
            if (index > 0) append(',')
            append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[")
            val latRad = zone.lat.coerceIn(-MAX_LAT_FOR_SCALE, MAX_LAT_FOR_SCALE) * PI / 180.0
            val dLat = zone.radiusM / METERS_PER_DEGREE_LAT
            val dLon = zone.radiusM / (METERS_PER_DEGREE_LAT * cos(latRad))
            for (i in 0..CIRCLE_POINTS) {
                // The ring is closed by repeating the first point (i == CIRCLE_POINTS).
                val angle = 2.0 * PI * (i % CIRCLE_POINTS) / CIRCLE_POINTS
                if (i > 0) append(',')
                append('[').append(zone.lon + dLon * cos(angle)).append(',').append(zone.lat + dLat * sin(angle)).append(']')
            }
            append("]]}}")
        }
        append("]}")
    }
}

internal const val EMPTY_FEATURES = "{\"type\":\"FeatureCollection\",\"features\":[]}"
