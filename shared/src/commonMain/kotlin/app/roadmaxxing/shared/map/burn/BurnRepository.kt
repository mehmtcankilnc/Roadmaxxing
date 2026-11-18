package app.roadmaxxing.shared.map.burn

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.onboarding.AppMetaStore
import app.roadmaxxing.shared.summary.SegmentResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Builds [BurnTimeline]s and remembers which drive's burn was already shown (`app_meta` key
 * `burn_shown_drive_id`; no schema change). Only the latest processed drive is ever animated.
 */
class BurnRepository(
    private val store: DriveStore,
    private val packProvider: RegionPackProvider,
    private val appMeta: AppMetaStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Timeline for the latest processed drive if it has fresh segments and has not been shown yet; else null. Runs off the main thread. */
    suspend fun pending(): BurnTimeline? {
        val lastId = store.observeLastProcessedDriveId().first() ?: return null
        if (appMeta.get(KEY_SHOWN) == lastId.toString()) return null
        return timelineFor(lastId)
    }

    /** Timeline for a given drive regardless of the shown flag (used by the summary/notification path); null if no fresh segments. */
    suspend fun timelineFor(driveId: Long): BurnTimeline? = withContext(dispatcher) {
        val ids = store.segmentsFirstDrivenBy(driveId)
        if (ids.isEmpty()) return@withContext null
        val found = SegmentResolver(packProvider.pack, store)
            .resolve(ids, ids.associateWith { driveId }, scanForMissing = true)
        if (found.isEmpty()) return@withContext null
        val fixes = store.points(driveId, Long.MIN_VALUE, Long.MAX_VALUE).sortedBy { it.timeMs }
        val timeline = BurnTimeline.build(
            driveId = driveId,
            roads = found.map { Triple(it.id, it.lats, it.lons) },
            fixLats = DoubleArray(fixes.size) { fixes[it].lat },
            fixLons = DoubleArray(fixes.size) { fixes[it].lon },
        )
        if (timeline.segmentCount == 0) null else timeline
    }

    /** Persists that [driveId]'s burn was shown. */
    fun markShown(driveId: Long) {
        appMeta.put(KEY_SHOWN, driveId.toString())
    }

    private companion object {
        const val KEY_SHOWN = "burn_shown_drive_id"
    }
}
