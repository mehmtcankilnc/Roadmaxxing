package app.roadmaxxing.shared.stats

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.domain.DriveRecord
import app.roadmaxxing.shared.domain.DriveStatus
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.progress.ProgressCalculator
import app.roadmaxxing.shared.summary.DISTRICT_COMPLETE_PERCENT
import app.roadmaxxing.shared.summary.SpeedStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

data class WeekBar(val weekStartEpochDay: Long, val newMeters: Double)

/** [progressFraction] is the position between the previous and the next milestone, 0..1. */
data class Milestone(val targetPercent: Double, val remainingMeters: Double, val progressFraction: Float)

data class DistrictStat(
    val regionId: Long,
    val name: String,
    val percent: Double,
    val percentMajor: Double,
    val percentLocal: Double,
    val completed: Boolean,
)

data class StatsSnapshot(
    val provinceName: String?,
    val provincePercent: Double,
    val majorPercent: Double,
    val localPercent: Double,
    val districtsCompleted: Int,
    val districtsTotal: Int,
    val newMetersThisWeek: Double,
    /** Exactly 8 weeks, oldest first, the current week last. */
    val weeks: List<WeekBar>,
    val streetsDriven: Int,
    val newStreetsThisWeek: Int,
    /** Null at 100 %. */
    val nextMilestone: Milestone?,
    val totalDistanceM: Double,
    val totalDurationMs: Long,
    val driveCount: Int,
    /** Null with no counted drives (and in the first emission after a change, until speeds are computed). */
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val districts: List<DistrictStat>,
)

/** Pure helpers of the stats computation, kept separate so they can be tested without a store or a pack. */
internal object StatsMath {
    /** Milestones in percent of the province's total road length. */
    val MILESTONES: List<Double> = listOf(1.0, 5.0, 10.0, 25.0, 50.0, 75.0, 90.0, 100.0)
    const val WEEKS: Int = 8
    const val SEGMENTS_PER_WAY: Long = 4096L

    /** Epoch day of the Monday of the week containing [ms] in [tz]. */
    fun weekStartDay(ms: Long, tz: TimeZone): Long {
        val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz).date
        return d.toEpochDays() - (d.dayOfWeek.isoDayNumber - 1)
    }

    /** Epoch ms of local midnight at the start of [epochDay] in [tz]. */
    fun dayStartMs(epochDay: Long, tz: TimeZone): Long =
        LocalDate.fromEpochDays(epochDay).atStartOfDayIn(tz).toEpochMilliseconds()

    /** Next milestone above the current progress, or null at 100 %. */
    fun nextMilestone(drivenDm: Long, totalDm: Long): Milestone? {
        if (totalDm <= 0L) return null
        val percent = drivenDm.toDouble() / totalDm * 100.0
        val next = MILESTONES.firstOrNull { it > percent } ?: return null
        val prev = MILESTONES.lastOrNull { it <= percent } ?: 0.0
        val remainingDm = (totalDm * next / 100.0 - drivenDm).coerceAtLeast(0.0)
        val fraction = ((percent - prev) / (next - prev)).coerceIn(0.0, 1.0)
        return Milestone(next, remainingDm / 10.0, fraction.toFloat())
    }
}

/**
 * Data of the Stats tab. Weeks start on Monday in the phone's time zone; totals count only drives with verdict Drive.
 * Heavy work runs on [dispatcher]. The first snapshot after a change has no speeds (they need the raw points of
 * every drive); a second one follows once the per-drive speeds are known (cached per drive).
 */
class StatsRepository(
    private val store: DriveStore,
    private val packProvider: RegionPackProvider,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val speedCache = HashMap<Pair<Long, Long>, SpeedStats>()
    private val speedLock = Mutex()

    /** Emits null while loading or if the pack is unavailable, then a snapshot; re-emits when drives or segments change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<StatsSnapshot?> = flow {
        emit(null)
        var first = true
        emitAll(
            combine(store.observeDrives(), store.observeDrivenSegmentCount()) { drives, _ -> drives }
                .transformLatest { drives ->
                    if (!first) delay(DEBOUNCE_MS)
                    first = false
                    val core = withContext(dispatcher) { runCatching { compute(drives) }.getOrNull() }
                    emit(core?.snapshot)
                    if (core != null && core.counted.isNotEmpty()) {
                        val speeds = withContext(dispatcher) { speedsOf(core.counted) }
                        if (speeds != null) {
                            emit(core.snapshot.copy(avgSpeedMps = speeds.avgMps, maxSpeedMps = speeds.maxMps))
                        }
                    }
                },
        )
    }.distinctUntilChanged()

    private class Core(val snapshot: StatsSnapshot, val counted: List<DriveRecord>)

    private fun compute(allDrives: List<DriveRecord>): Core {
        val pack = packProvider.pack
        val tz = timeZone()
        val counted = allDrives.filter {
            it.status == DriveStatus.Processed && it.summary?.verdict == DriveVerdict.Drive
        }
        val drivenIds = store.drivenSegmentIds()
        val progress = ProgressCalculator(pack).compute(drivenIds).associateBy { it.regionId }
        val provinceProgress = progress[pack.provinceId]

        val districts = pack.districts.map { r ->
            val p = progress[r.id]
            val percent = p?.percent ?: 0.0
            DistrictStat(
                r.id, r.name, percent, p?.percentMajor ?: 0.0, p?.percentLocal ?: 0.0,
                percent >= DISTRICT_COMPLETE_PERCENT,
            )
        }.sortedWith(compareByDescending<DistrictStat> { it.percent }.thenBy { it.name })

        val currentWeek = StatsMath.weekStartDay(clock(), tz)
        val firstWeek = currentWeek - 7L * (StatsMath.WEEKS - 1)
        val weekDm = LongArray(StatsMath.WEEKS)
        var distance = 0.0
        var duration = 0L
        for (d in counted) {
            val s = d.summary ?: continue
            distance += s.distanceM
            duration += s.durationMs
            val idx = (StatsMath.weekStartDay(d.startMs, tz) - firstWeek).floorDiv(7L).toInt()
            if (idx in 0 until StatsMath.WEEKS) weekDm[idx] += s.newLengthDm
        }
        val weeks = List(StatsMath.WEEKS) { WeekBar(firstWeek + 7L * it, weekDm[it] / 10.0) }

        // A street is a distinct OSM way; "new this week" = ways none of whose segments was driven before this week.
        val ways = HashSet<Long>()
        for (id in drivenIds) ways.add(id / StatsMath.SEGMENTS_PER_WAY)
        val sinceIds = store.segmentsFirstDrivenSince(StatsMath.dayStartMs(currentWeek, tz))
        val newWays = HashSet<Long>()
        if (sinceIds.isNotEmpty()) {
            val before = HashSet<Long>()
            for (id in drivenIds) if (id !in sinceIds) before.add(id / StatsMath.SEGMENTS_PER_WAY)
            for (id in sinceIds) {
                val w = id / StatsMath.SEGMENTS_PER_WAY
                if (w !in before) newWays.add(w)
            }
        }

        val drivenDm = provinceProgress?.drivenDm ?: 0L
        val totalDm = provinceProgress?.totalDm ?: pack.province.lengthDmAll
        val snapshot = StatsSnapshot(
            provinceName = pack.province.name,
            provincePercent = provinceProgress?.percent ?: 0.0,
            majorPercent = provinceProgress?.percentMajor ?: 0.0,
            localPercent = provinceProgress?.percentLocal ?: 0.0,
            districtsCompleted = districts.count { it.completed },
            districtsTotal = districts.size,
            newMetersThisWeek = weeks.last().newMeters,
            weeks = weeks,
            streetsDriven = ways.size,
            newStreetsThisWeek = newWays.size,
            nextMilestone = StatsMath.nextMilestone(drivenDm, totalDm),
            totalDistanceM = distance,
            totalDurationMs = duration,
            driveCount = counted.size,
            avgSpeedMps = null,
            maxSpeedMps = null,
            districts = districts,
        )
        return Core(snapshot, counted)
    }

    /**
     * Average = total distance / total moving time (a drive's moving time is its distance / its moving average;
     * drives without one count their full duration). Maximum = highest per-drive maximum. Null when nothing is known.
     */
    private suspend fun speedsOf(drives: List<DriveRecord>): SpeedStats? = speedLock.withLock {
        var distance = 0.0
        var movingS = 0.0
        var max: Double? = null
        for (d in drives) {
            val s = d.summary ?: continue
            val stats = speedCache.getOrPut(d.id to d.startMs) {
                val from = d.keepFromMs ?: d.startMs
                val to = d.keepToMs ?: (from + s.durationMs)
                SpeedStats.compute(store.points(d.id, from, to))
            }
            stats.maxMps?.takeIf { it <= MAX_CAR_SPEED_MPS }?.let { m -> max = maxOf(max ?: m, m) }
            val avg = stats.avgMps
            // A drive whose own average is impossible for a car (old data, GPS jump) is left out of the average.
            if (avg != null && avg > MAX_CAR_SPEED_MPS) continue
            movingS += if (avg != null && avg > 0.0) s.distanceM / avg else s.durationMs / 1000.0
            distance += s.distanceM
        }
        val avgAll = if (movingS > 0.0 && distance > 0.0) distance / movingS else null
        if (avgAll == null && max == null) null else SpeedStats(avgAll, max)
    }

    private companion object {
        const val DEBOUNCE_MS = 100L

        /** Same plausibility cap the speed filter uses (70 m/s, about 252 km/h); higher per-drive values are ignored. */
        const val MAX_CAR_SPEED_MPS = SpeedStats.MAX_PLAUSIBLE_MPS
    }
}
