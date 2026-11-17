package app.roadmaxxing.shared.matching

/**
 * Tunables of [RoadMatcher]. Defaults were chosen on the Adana GPX routes (test-data/gpx) and checked
 * on extra noise realisations of the same routes (GpxAccuracyTest.robustOnExtraNoiseSeeds).
 */
data class MatcherConfig(
    /** A segment counts as driven when at least this share of its length is covered. */
    val drivenCoverage: Double = 0.70,
    /**
     * GPS position sigma (m) used when a point has no accuracy. Larger than the raw per-axis noise
     * (~6 m) on purpose: the noise is time-correlated, so consecutive points are not independent evidence.
     */
    val defaultSigmaM: Double = 10.0,
    /** Bounds for a point's sigma taken from its reported accuracy. */
    val minSigmaM: Double = 8.0,
    val maxSigmaM: Double = 20.0,
    /** Candidate search radius = max(minRadiusM, radiusSigmas x sigma). */
    val minRadiusM: Double = 35.0,
    val radiusSigmas: Double = 4.5,
    /** At most this many candidate segments per point (nearest first). */
    val maxCandidates: Int = 20,
    /** Points closer than this to the last kept point are dropped (stops pile up points). */
    val minSpacingM: Double = 8.0,
    /** Speed (m/s) above which a heading is trusted. */
    val minHeadingSpeedMps: Double = 2.5,
    /** A reported course further than this from the motion between neighbouring points is ignored. */
    val maxReportedHeadingDisagreementDeg: Double = 45.0,
    /** Weight of heading agreement: log-prob = -kappa x (1 - cos(diff)). */
    val headingKappa: Double = 4.0,
    /** Transition scale (m): log-prob = -|route - straight| / beta. */
    val transitionBetaM: Double = 6.0,
    /** Additional transition scale proportional to the straight-line distance. */
    val transitionBetaPerM: Double = 0.1,
    /**
     * Backward movement along a segment up to this distance is treated as GPS jitter: allowed at a cost of
     * [backwardPenaltyPerM] per metre and never counted as coverage (so driving a oneway the wrong way paints nothing).
     */
    val backwardToleranceM: Double = 15.0,
    val backwardPenaltyPerM: Double = 0.2,
    /**
     * Log-prob cost per segment boundary crossed in a transition (prefers simpler paths between near-identical
     * alternatives). Kept small: larger values (>= 1.5) make junction-dense roads lose against a parallel road
     * with fewer junctions even on exact positions (seen on the emulator replay of main-roads, D400 interchange).
     */
    val segmentChangePenalty: Double = 1.0,
    /** Fixed log-prob cost of a jump (no road connection between two candidates). */
    val jumpPenalty: Double = 18.0,
    /** A point counts as matched when it is within this distance of its road and connected to a neighbour. */
    val matchedToleranceM: Double = 25.0,
    /** Local (residential) roads cost nothing up to this speed (m/s, 40 km/h) ... */
    val localRoadFreeSpeedMps: Double = 11.0,
    /** ... then this much log-prob per m/s above it ... */
    val localRoadPenaltyPerMps: Double = 0.3,
    /** ... capped here. */
    val localRoadMaxPenalty: Double = 2.5,
)
