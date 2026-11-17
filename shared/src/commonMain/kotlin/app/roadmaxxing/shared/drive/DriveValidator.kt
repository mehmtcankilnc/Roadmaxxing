package app.roadmaxxing.shared.drive

/** Why a recorded trace was or was not accepted as a drive. */
enum class DriveVerdict {
    Drive,
    TooShort,
    TooSlow,
    TooFast,

    /** The trace does not follow roads (train, boat, ferry). */
    OffRoad,
}

data class DriveValidationConfig(
    /** Shorter traces are noise (parking manoeuvre, GPS drift). */
    val minDistanceM: Double = 300.0,
    val minMovingDurationMs: Long = 60_000,
    /**
     * Average over the whole (trimmed) trace: 2 m/s = 7.2 km/h. Walking averages ~1.3 m/s; even a drive with
     * a long jam stays above it once the stationary head and tail are trimmed.
     */
    val minAvgSpeedMps: Double = 2.0,
    /** 250 km/h: planes and high-speed trains; no road vehicle the app counts reaches it. */
    val maxSpeedMps: Double = 70.0,
    /** Minimum share of fixes the matcher snapped to a road. See [DriveValidator]. */
    val minMatchedPointFraction: Double = 0.5,
)

/**
 * Accepts or rejects a finished trace. Runs after matching; a rejected trace writes no driven segments.
 *
 * [DriveValidationConfig.minMatchedPointFraction] = 0.5 (B1's recommendation): every car GPX route snaps at 1.00
 * (clean and noisy), the train at 0.14-0.19. 0.5 leaves margin for real drives through parking lots, service
 * roads or unmapped streets, whose points stay unmatched. Walks also snap at 1.00 (they are on roads): they are
 * rejected by speed, never by this rule. A rejected trace stores no segments even though the matcher returns some.
 */
class DriveValidator(private val config: DriveValidationConfig = DriveValidationConfig()) {

    /** Checks that need no matching; lets the caller skip the expensive matcher for obvious non-drives. */
    fun preCheck(stats: TraceStats): DriveVerdict = when {
        stats.distanceM < config.minDistanceM || stats.movingDurationMs < config.minMovingDurationMs -> DriveVerdict.TooShort
        stats.avgSpeedMps < config.minAvgSpeedMps -> DriveVerdict.TooSlow
        stats.maxSpeedMps > config.maxSpeedMps -> DriveVerdict.TooFast
        else -> DriveVerdict.Drive
    }

    fun validate(stats: TraceStats, matchedPointFraction: Double): DriveVerdict {
        val pre = preCheck(stats)
        if (pre != DriveVerdict.Drive) return pre
        return if (matchedPointFraction < config.minMatchedPointFraction) DriveVerdict.OffRoad else DriveVerdict.Drive
    }
}
