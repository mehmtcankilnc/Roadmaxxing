package app.roadmaxxing.shared.sync

import app.roadmaxxing.shared.summary.GeoPoint

data class RemoteZone(val kind: String, val name: String?, val lat: Double, val lon: Double, val radiusM: Int)

data class RemoteDrive(
    val syncId: String, val startMs: Long, val endMs: Long?, val verdict: String?,
    val distanceM: Double?, val durationMs: Long?, val avgSpeedMps: Double?, val maxSpeedMps: Double?,
    val newLengthDm: Long?, val route: List<GeoPoint>,   // simplified, <= 300 points
    val firstDrivenSegmentIds: List<Long>,
)

data class RemoteProfile(val updatedAtMs: Long, val settings: Map<String, String>, val zones: List<RemoteZone>)
