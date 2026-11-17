package app.roadmaxxing.shared.domain

import app.roadmaxxing.shared.drive.DriveVerdict

enum class DriveStatus { Recording, Pending, Processed }

/** A stored drive as the UI and processing see it. */
data class DriveRecord(
    val id: Long,
    val startMs: Long,
    val startReason: String,
    val status: DriveStatus,
    val endDecisionMs: Long?,
    val endReason: String?,
    val keepFromMs: Long?,
    val keepToMs: Long?,
    val summary: DriveSummary?,
)

/** Result of matching and validating a finished drive. */
data class DriveSummary(
    val verdict: DriveVerdict,
    val distanceM: Double,
    val durationMs: Long,
    val pointCount: Int,
    val matchedPointFraction: Double,
    val drivenSegmentCount: Int,
    /** Length of segments driven for the first time by this drive, decimetres. */
    val newLengthDm: Long,
    /** Wall time spent matching and storing. */
    val processingMs: Long,
    val processedAtMs: Long,
)
