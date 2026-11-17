package app.roadmaxxing.shared.summary

data class GeoPoint(val lat: Double, val lon: Double)

/** [fresh] = first driven in this drive. */
data class CardPolyline(val points: List<GeoPoint>, val fresh: Boolean)

data class DriveCardData(
    val driveId: Long, val startMs: Long, val endMs: Long,
    val distanceM: Double, val durationMs: Long,
    /** null when unknown */
    val avgSpeedMps: Double?, val maxSpeedMps: Double?,
    val newLengthM: Double,
    /** Simplified, privacy-trimmed (first and last 300 m removed). */
    val route: List<CardPolyline>,
    val regionName: String?,
)

data class CityCardData(
    val regionId: Long, val name: String,
    val percent: Double, val percentMajor: Double, val percentLocal: Double,
    val districtsCompleted: Int, val districtsTotal: Int,
    /** New road length in the last 7 days. */
    val weekNewLengthM: Double,
    val boundary: List<List<GeoPoint>>,
    val driven: List<List<GeoPoint>>,
)
