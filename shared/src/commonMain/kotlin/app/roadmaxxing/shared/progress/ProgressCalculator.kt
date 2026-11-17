package app.roadmaxxing.shared.progress

import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.region.RoadLayer

/** Completion of one region. Percent = driven length_dm / region length_dm x 100. */
data class RegionProgress(
    val regionId: Long,
    val drivenDmMajor: Long,
    val drivenDmLocal: Long,
    val totalDmMajor: Long,
    val totalDmLocal: Long,
) {
    val drivenDm: Long get() = drivenDmMajor + drivenDmLocal
    val totalDm: Long get() = totalDmMajor + totalDmLocal
    val percent: Double get() = pct(drivenDm, totalDm)
    val percentMajor: Double get() = pct(drivenDmMajor, totalDmMajor)
    val percentLocal: Double get() = pct(drivenDmLocal, totalDmLocal)

    private fun pct(driven: Long, total: Long): Double = if (total <= 0L) 0.0 else driven.toDouble() / total * 100.0
}

/**
 * Computes completion from the set of driven segment ids. Every segment counts once with its
 * full length (a set cannot hold duplicates); ids not in the pack are ignored.
 */
class ProgressCalculator(private val pack: RegionPack) {
    /** Province first, then every district of the pack (same order as [RegionPack.regions]). */
    fun compute(drivenSegmentIds: Set<Long>): List<RegionProgress> {
        val major = HashMap<Long, Long>()
        val local = HashMap<Long, Long>()
        for (id in drivenSegmentIds) {
            val s = pack.summary(id) ?: continue
            val target = if (s.layer == RoadLayer.Major) major else local
            target[s.districtId] = (target[s.districtId] ?: 0L) + s.lengthDm
            target[s.provinceId] = (target[s.provinceId] ?: 0L) + s.lengthDm
        }
        return pack.regions.map { r ->
            RegionProgress(
                regionId = r.id,
                drivenDmMajor = major[r.id] ?: 0L,
                drivenDmLocal = local[r.id] ?: 0L,
                totalDmMajor = r.lengthDmMajor,
                totalDmLocal = r.lengthDmLocal,
            )
        }
    }
}
