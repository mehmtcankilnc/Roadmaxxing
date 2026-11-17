package app.roadmaxxing.shared.domain

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.progress.ProgressCalculator
import app.roadmaxxing.shared.region.Region
import app.roadmaxxing.shared.region.RegionPack
import app.roadmaxxing.shared.region.RegionPackSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Loads the embedded region pack once, on first use (it parses manifest and regions JSON). */
class RegionPackProvider(private val source: RegionPackSource) {
    val pack: RegionPack by lazy { RegionPack(source) }
}

data class RegionProgressItem(
    val region: Region,
    val percent: Double,
    val percentMajor: Double,
    val percentLocal: Double,
    val drivenDm: Long,
)

/** Province first, then districts sorted by completion. */
data class ProgressSnapshot(
    val province: RegionProgressItem?,
    val districts: List<RegionProgressItem>,
    val drivenSegmentCount: Long,
)

class ProgressRepository(
    private val store: DriveStore,
    private val packProvider: RegionPackProvider,
    private val cpuDispatcher: CoroutineDispatcher,
) {
    fun observe(): Flow<ProgressSnapshot> = store.observeDrivenSegmentCount()
        .map { count -> compute(count) }
        .flowOn(cpuDispatcher)

    private fun compute(count: Long): ProgressSnapshot {
        val pack = packProvider.pack
        val ids = store.drivenSegmentIds()
        val byId = pack.regions.associateBy { it.id }
        val items = ProgressCalculator(pack).compute(ids).mapNotNull { p ->
            val region = byId[p.regionId] ?: return@mapNotNull null
            RegionProgressItem(region, p.percent, p.percentMajor, p.percentLocal, p.drivenDmMajor + p.drivenDmLocal)
        }
        val province = items.firstOrNull { it.region.level == 1 }
        val districts = items.filter { it.region.level == 2 }
            .sortedWith(compareByDescending<RegionProgressItem> { it.percent }.thenBy { it.region.name })
        return ProgressSnapshot(province, districts, count)
    }
}
