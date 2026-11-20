package app.roadmaxxing.shared.milestones

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.onboarding.AppMetaStore
import app.roadmaxxing.shared.progress.ProgressCalculator
import app.roadmaxxing.shared.region.Region
import app.roadmaxxing.shared.summary.DISTRICT_COMPLETE_PERCENT
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface MilestoneEvent {
    /** Stable key, e.g. "p:<regionId>:5" or "d:<regionId>". */
    val key: String

    data class ProvinceThreshold(
        override val key: String,
        val regionId: Long,
        val name: String,
        val percent: Int,
    ) : MilestoneEvent

    data class DistrictCompleted(override val key: String, val regionId: Long, val name: String) : MilestoneEvent
}

/** Everything newly reached in one drive; shown on one screen. */
data class Celebration(
    val driveId: Long?,
    /** Highest threshold reached in this drive, or null. */
    val province: MilestoneEvent.ProvinceThreshold?,
    /** Districts completed in this drive. */
    val districts: List<MilestoneEvent.DistrictCompleted>,
)

/** Driven and total road length of one region, in decimetres. */
internal data class RegionLengths(val regionId: Long, val name: String, val drivenDm: Long, val totalDm: Long)

internal data class ProgressState(val province: RegionLengths, val districts: List<RegionLengths>)

/** Where [MilestoneRepository] reads progress from; faked in tests. */
internal fun interface MilestoneProgressSource {
    /** Current progress; segments first driven by [excludeDriveId] are left out (used to seed "before this drive"). */
    fun state(excludeDriveId: Long?): ProgressState
}

/**
 * Tracks province thresholds ([PROVINCE_THRESHOLDS]) and completed districts ([DISTRICT_COMPLETE_PERCENT]) and queues
 * one [Celebration] per drive. State lives in `app_meta` (no schema change):
 *  - `milestones_achieved`: comma-separated event keys; absent = feature never ran (first run seeds silently).
 *  - `milestones_pending`: one line per queued celebration: driveId or `-`, TAB, province key or `-`, TAB,
 *    comma-separated district keys. Names are re-resolved from the region pack on load.
 *
 * Comparisons use integer decimetre lengths, so there is no rounding error. The repository observes
 * driven-segment changes itself; [evaluate] is idempotent, so explicit calls are safe too.
 * Create it at app start so the first run (silent seed) happens before the user's first drive.
 */
class MilestoneRepository internal constructor(
    private val progress: MilestoneProgressSource,
    private val appMeta: AppMetaStore,
    private val dispatcher: CoroutineDispatcher,
    private val names: (Long) -> String?,
    triggers: Flow<Long?>?,
    private val scope: CoroutineScope,
) {
    constructor(
        store: DriveStore,
        packProvider: RegionPackProvider,
        appMeta: AppMetaStore,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        progress = StoreProgressSource(store, packProvider),
        appMeta = appMeta,
        dispatcher = dispatcher,
        names = { id -> packProvider.pack.regions.firstOrNull { it.id == id }?.name },
        triggers = combine(store.observeDrivenSegmentCount(), store.observeLastProcessedDriveId()) { _, id -> id },
        scope = CoroutineScope(SupervisorJob() + dispatcher),
    )

    private val mutex = Mutex()
    private val queue = MutableStateFlow(loadQueue())
    private val pendingFlow = MutableStateFlow(queue.value.firstOrNull())

    /** Oldest not-yet-celebrated celebration, or null. */
    val pending: StateFlow<Celebration?> = pendingFlow

    init {
        if (triggers != null) {
            scope.launch {
                var first = true
                triggers.distinctUntilChanged().collect { driveId ->
                    if (!first) delay(DEBOUNCE_MS)
                    // The very first pass of a fresh install seeds silently (K2), also for an already processed drive.
                    runCatching { run(driveId, seedAll = first) }
                    first = false
                }
            }
        }
    }

    /**
     * Evaluates the current progress against the stored thresholds; newly reached events are attributed to
     * [driveId], stored and queued as one [Celebration]. Returns the new events (empty if none). Idempotent.
     */
    suspend fun evaluate(driveId: Long?): List<MilestoneEvent> = run(driveId, seedAll = false)

    /**
     * Records every threshold passed right now as achieved without queuing a celebration. Used after drives were
     * imported from the cloud (restore / merge): milestones crossed only because of imported data must stay silent.
     * Idempotent; the later automatic evaluation then finds nothing new.
     */
    suspend fun recordSilently() {
        withContext(dispatcher) {
            mutex.withLock {
                val achieved = (appMeta.get(KEY_ACHIEVED) ?: "").split(',').filter { it.isNotEmpty() }
                val now = reached(progress.state(null)).map { it.key }
                val merged = (achieved + now).distinct()
                if (merged != achieved || appMeta.get(KEY_ACHIEVED) == null) {
                    appMeta.put(KEY_ACHIEVED, merged.joinToString(","))
                }
            }
        }
    }

    /** Removes [celebration] from the queue (it stays achieved). */
    fun markCelebrated(celebration: Celebration) {
        queue.value = queue.value.filterNot { it == celebration }
        pendingFlow.value = queue.value.firstOrNull()
        scope.launch { mutex.withLock { saveQueue(queue.value) } }
    }

    /** Re-reads the queue from storage (used after an erase, which also removed the achieved state). */
    fun reload() {
        queue.value = loadQueue()
        pendingFlow.value = queue.value.firstOrNull()
    }

    private suspend fun run(driveId: Long?, seedAll: Boolean): List<MilestoneEvent> = withContext(dispatcher) {
        mutex.withLock {
            if (appMeta.get(KEY_ACHIEVED) == null) {
                // First run: record everything already passed and queue nothing. When called for a drive, "already
                // passed" means before that drive, so that drive's own milestones still celebrate.
                val exclude = if (seedAll) null else driveId
                appMeta.put(KEY_ACHIEVED, reached(progress.state(exclude)).joinToString(",") { it.key })
                if (exclude == null) return@withLock emptyList()
            }
            val achieved = (appMeta.get(KEY_ACHIEVED) ?: "").split(',').filter { it.isNotEmpty() }
            val fresh = reached(progress.state(null)).filter { it.key !in achieved }
            if (fresh.isEmpty()) return@withLock emptyList()
            appMeta.put(KEY_ACHIEVED, (achieved + fresh.map { it.key }).joinToString(","))
            val celebration = Celebration(
                driveId = driveId,
                province = fresh.filterIsInstance<MilestoneEvent.ProvinceThreshold>().maxByOrNull { it.percent },
                districts = fresh.filterIsInstance<MilestoneEvent.DistrictCompleted>(),
            )
            queue.value = queue.value + celebration
            saveQueue(queue.value)
            pendingFlow.value = queue.value.firstOrNull()
            fresh
        }
    }

    private fun reached(state: ProgressState): List<MilestoneEvent> {
        val out = ArrayList<MilestoneEvent>()
        val p = state.province
        if (p.totalDm > 0L) {
            for (t in PROVINCE_THRESHOLDS) {
                if (p.drivenDm * 100L >= p.totalDm * t) {
                    out += MilestoneEvent.ProvinceThreshold("p:${p.regionId}:$t", p.regionId, p.name, t)
                }
            }
        }
        for (d in state.districts) {
            if (d.totalDm > 0L && d.drivenDm * 100.0 >= d.totalDm * DISTRICT_COMPLETE_PERCENT) {
                out += MilestoneEvent.DistrictCompleted("d:${d.regionId}", d.regionId, d.name)
            }
        }
        return out
    }

    private fun saveQueue(list: List<Celebration>) {
        val text = list.joinToString("\n") { c ->
            listOf(
                c.driveId?.toString() ?: "-",
                c.province?.key ?: "-",
                c.districts.joinToString(",") { it.key },
            ).joinToString("\t")
        }
        appMeta.put(KEY_PENDING, text)
    }

    private fun loadQueue(): List<Celebration> {
        val raw = appMeta.get(KEY_PENDING).orEmpty()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            val driveId = if (parts[0] == "-") null else (parts[0].toLongOrNull() ?: return@mapNotNull null)
            val province = if (parts[1] == "-") null else parseProvince(parts[1])
            val districts = parts[2].split(',').filter { it.isNotEmpty() }.mapNotNull(::parseDistrict)
            if (province == null && districts.isEmpty()) null else Celebration(driveId, province, districts)
        }
    }

    private fun parseProvince(key: String): MilestoneEvent.ProvinceThreshold? {
        val p = key.split(':')
        if (p.size != 3 || p[0] != "p") return null
        val id = p[1].toLongOrNull() ?: return null
        val percent = p[2].toIntOrNull() ?: return null
        return MilestoneEvent.ProvinceThreshold(key, id, names(id) ?: return null, percent)
    }

    private fun parseDistrict(key: String): MilestoneEvent.DistrictCompleted? {
        val p = key.split(':')
        if (p.size != 2 || p[0] != "d") return null
        val id = p[1].toLongOrNull() ?: return null
        return MilestoneEvent.DistrictCompleted(key, id, names(id) ?: return null)
    }

    companion object {
        val PROVINCE_THRESHOLDS: List<Int> = listOf(1, 5, 10, 25, 50, 75, 90, 100)
        private const val KEY_ACHIEVED = "milestones_achieved"
        private const val KEY_PENDING = "milestones_pending"
        private const val DEBOUNCE_MS = 200L
    }
}

private class StoreProgressSource(
    private val store: DriveStore,
    private val packProvider: RegionPackProvider,
) : MilestoneProgressSource {
    override fun state(excludeDriveId: Long?): ProgressState {
        val pack = packProvider.pack
        var ids = store.drivenSegmentIds()
        if (excludeDriveId != null) ids = ids - store.segmentsFirstDrivenBy(excludeDriveId)
        val byId = ProgressCalculator(pack).compute(ids).associateBy { it.regionId }
        fun lengths(r: Region): RegionLengths {
            val p = byId[r.id]
            return RegionLengths(r.id, r.name, p?.drivenDm ?: 0L, p?.totalDm ?: r.lengthDmAll)
        }
        return ProgressState(lengths(pack.province), pack.districts.map(::lengths))
    }
}
