package app.roadmaxxing.shared.sync

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.db.Drive
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.map.layers.MapLayerPreferences
import app.roadmaxxing.shared.milestones.MilestoneRepository
import app.roadmaxxing.shared.onboarding.AppMetaStore
import app.roadmaxxing.shared.privacy.PrivacyZoneRepository
import app.roadmaxxing.shared.settings.SettingsRepository
import app.roadmaxxing.shared.summary.CardGeometry
import app.roadmaxxing.shared.summary.GeoPoint
import app.roadmaxxing.shared.summary.SpeedStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Local side of cloud sync: sync ids on drives, the upload queue, import of cloud drives and the synced profile.
 * Everything runs on [dispatcher]. Raw GPS fixes are never read for anything but the simplified route.
 *
 * Profile change detection needs no observers: [localProfile] compares the current profile content with a stored
 * fingerprint and, when it differs, stamps `profile_updated_at` with the time it noticed (the next sync trigger).
 */
class SyncStore(
    private val database: RoadmaxxingDatabase,
    private val store: DriveStore,
    private val appMeta: AppMetaStore,
    private val zones: PrivacyZoneRepository,
    private val settings: SettingsRepository,
    private val milestones: MilestoneRepository,
    private val layers: MapLayerPreferences,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val drives get() = database.driveQueries
    private val segments get() = database.drivenSegmentQueries

    /** The account (uid) whose cloud data this phone's data is bound to; null = not bound yet. */
    var boundUid: String?
        get() = appMeta.get(KEY_OWNER)
        set(value) {
            if (value == null) database.appMetaQueries.deleteValue(KEY_OWNER) else appMeta.put(KEY_OWNER, value)
        }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun ensureSyncIds() = withContext(dispatcher) {
        database.transaction {
            for (id in drives.selectIdsWithoutSyncId().executeAsList()) {
                drives.setSyncId(Uuid.random().toString(), id)
            }
        }
    }

    suspend fun localSyncIds(): Set<String> = withContext(dispatcher) {
        drives.selectAllSyncIds().executeAsList().filterNotNull().toSet()
    }

    suspend fun unsyncedDrives(): List<RemoteDrive> = withContext(dispatcher) {
        drives.selectUnsyncedIds().executeAsList().mapNotNull { drives.selectById(it).executeAsOneOrNull() }.map { toRemote(it) }
    }

    suspend fun markSynced(syncIds: Collection<String>, atMs: Long) = withContext(dispatcher) {
        database.transaction { for (id in syncIds) drives.markSynced(atMs, id) }
    }

    /**
     * Inserts the drives this phone does not have yet as PROCESSED drives without trace points (their simplified
     * route is kept in `remote_route`) and their segments. A segment that is already driven keeps the EARLIEST first
     * drive, so importing an older drive moves the "first driven" credit to it. Drives are imported in start order, in
     * one transaction, and are marked synced. Idempotent: drives whose sync id exists locally are skipped.
     */
    suspend fun importDrives(drives: List<RemoteDrive>) = withContext(dispatcher) {
        val known = this@SyncStore.drives.selectAllSyncIds().executeAsList().filterNotNull().toMutableSet()
        val now = clock()
        database.transaction {
            for (d in drives.sortedWith(compareBy({ it.startMs }, { it.syncId }))) {
                if (!known.add(d.syncId)) continue
                val end = d.endMs ?: (d.startMs + (d.durationMs ?: 0L))
                this@SyncStore.drives.insertImported(
                    start_ms = d.startMs, end_decision_ms = end, keep_from_ms = d.startMs, keep_to_ms = end,
                    verdict = d.verdict, distance_m = d.distanceM, duration_ms = d.durationMs,
                    driven_segment_count = d.firstDrivenSegmentIds.size.toLong(), new_length_dm = d.newLengthDm,
                    processed_at_ms = end, sync_id = d.syncId, synced_at_ms = now,
                    remote_route = d.route.takeIf { it.isNotEmpty() }?.let { SyncEncoding.encodeRoute(it) },
                )
                val driveId = this@SyncStore.drives.lastInsertedId().executeAsOne()
                for (segment in d.firstDrivenSegmentIds) {
                    segments.insertIfNew(segment, d.startMs, driveId)
                    segments.updateIfEarlier(d.startMs, driveId, segment, d.startMs)
                }
            }
        }
    }

    /**
     * The synced profile. [RemoteProfile.updatedAtMs] is the stored stamp; when the content changed since it was
     * stored, it is re-stamped with [nowMs]. A pristine profile (no stamp, no settings, no zones; milestones alone
     * do not count, they are seeded silently) has stamp 0, so any cloud profile wins over it.
     */
    suspend fun localProfile(nowMs: Long): RemoteProfile = withContext(dispatcher) { profileSync(nowMs) }

    /**
     * Last-write-wins on the profile: when [p] is newer than the local profile, unit, quiet hours, layer preferences
     * and zones are replaced by [p]'s. Milestones are a growing set, so they are always merged (union), never lost.
     */
    suspend fun applyProfile(p: RemoteProfile) = withContext(dispatcher) {
        val local = profileSync(clock())
        val wasNewer = p.updatedAtMs > local.updatedAtMs
        database.transaction {
            if (wasNewer) {
                for (key in SETTING_KEYS) {
                    if (key == KEY_MILESTONES) continue
                    val value = p.settings[key]
                    if (value == null) database.appMetaQueries.deleteValue(key) else appMeta.put(key, value)
                }
            }
            val remoteMilestones = p.settings[KEY_MILESTONES].orEmpty().split(',').filter { it.isNotEmpty() }
            if (remoteMilestones.isNotEmpty()) {
                val localMilestones = appMeta.get(KEY_MILESTONES).orEmpty().split(',').filter { it.isNotEmpty() }
                val merged = (localMilestones + remoteMilestones).distinct()
                if (merged.isNotEmpty() && merged != localMilestones) appMeta.put(KEY_MILESTONES, merged.joinToString(","))
            }
            if (wasNewer) {
                database.privacyZoneQueries.deleteAll()
                val now = clock()
                for (z in p.zones) {
                    database.privacyZoneQueries.insertZone(
                        z.kind.uppercase(), z.name, z.lat, z.lon,
                        z.radiusM.coerceIn(PrivacyZoneRepository.MIN_RADIUS_M, PrivacyZoneRepository.MAX_RADIUS_M).toLong(), now,
                    )
                }
                appMeta.put(KEY_PROFILE_AT, p.updatedAtMs.toString())
                appMeta.put(KEY_PROFILE_FP, fingerprint(p.settings, p.zones))
            }
        }
        if (wasNewer || !p.settings[KEY_MILESTONES].isNullOrEmpty()) {
            zones.reload()
            settings.reload()
            layers.reload()
            milestones.reload()
        }
    }

    /** Processed drives not uploaded yet (including ones that still lack a sync id). */
    suspend fun pendingCount(): Int = withContext(dispatcher) { drives.countUnsynced().executeAsOne().toInt() }

    /** Forgets the bound account and every "uploaded" flag (sync ids stay), e.g. after an erase or account switch. */
    suspend fun clearSyncState() = withContext(dispatcher) {
        database.transaction {
            database.appMetaQueries.deleteValue(KEY_OWNER)
            drives.clearSynced()
        }
    }

    private fun profileSync(nowMs: Long): RemoteProfile {
        val settingValues = LinkedHashMap<String, String>()
        for (key in SETTING_KEYS) appMeta.get(key)?.let { settingValues[key] = it }
        val remoteZones = zones.zones.value.map { RemoteZone(it.kind.name.uppercase(), it.name, it.lat, it.lon, it.radiusM) }
        val fp = fingerprint(settingValues, remoteZones)
        var updated = appMeta.get(KEY_PROFILE_AT)?.toLongOrNull()
        if (appMeta.get(KEY_PROFILE_FP) != fp) {
            val pristine = updated == null &&
                settingValues.keys.all { it == KEY_MILESTONES } && remoteZones.isEmpty()
            if (pristine) {
                updated = 0L
            } else {
                updated = nowMs
                appMeta.put(KEY_PROFILE_AT, nowMs.toString())
                appMeta.put(KEY_PROFILE_FP, fp)
            }
        }
        return RemoteProfile(updated ?: 0L, settingValues, remoteZones)
    }

    private fun fingerprint(settingValues: Map<String, String>, remoteZones: List<RemoteZone>): String {
        val s = SETTING_KEYS.joinToString("\n") { "$it=${settingValues[it].orEmpty()}" }
        val z = remoteZones.joinToString("\n") { "${it.kind}|${it.name.orEmpty()}|${it.lat}|${it.lon}|${it.radiusM}" }
        return "$s\n--\n$z"
    }

    private fun toRemote(row: Drive): RemoteDrive {
        val from = row.keep_from_ms ?: row.start_ms
        val to = row.keep_to_ms ?: (from + (row.duration_ms ?: 0L))
        val isDrive = row.verdict == DriveVerdict.Drive.name
        var route: List<GeoPoint> = emptyList()
        var speeds = SpeedStats(null, null)
        if (isDrive) {
            val points = store.points(row.id, from, to).sortedBy { it.timeMs }
            speeds = SpeedStats.compute(points)
            route = limitRoute(points.map { GeoPoint(it.lat, it.lon) })
        }
        return RemoteDrive(
            syncId = row.sync_id ?: error("drive ${row.id} has no sync id"),
            startMs = row.start_ms, endMs = to, verdict = row.verdict,
            distanceM = row.distance_m, durationMs = row.duration_ms,
            avgSpeedMps = speeds.avgMps, maxSpeedMps = speeds.maxMps, newLengthDm = row.new_length_dm,
            route = route,
            firstDrivenSegmentIds = if (isDrive) store.segmentsFirstDrivenBy(row.id).sorted() else emptyList(),
        )
    }

    companion object {
        const val KEY_OWNER = "sync_owner_uid"
        const val KEY_PROFILE_AT = "profile_updated_at"
        const val KEY_PROFILE_FP = "profile_fingerprint"
        const val MAX_ROUTE_POINTS = 300
        private const val KEY_MILESTONES = "milestones_achieved"

        /** app_meta keys that travel in the profile document. Onboarding, consent and device-local flags do not. */
        val SETTING_KEYS = listOf(
            "distance_unit", "quiet_hours", MapLayerPreferences.KEY_UNDISCOVERED, MapLayerPreferences.KEY_PRIVACY_ZONES,
            KEY_MILESTONES,
        )

        /** Douglas-Peucker with a growing tolerance until at most [MAX_ROUTE_POINTS] points remain. */
        internal fun limitRoute(points: List<GeoPoint>): List<GeoPoint> {
            if (points.size <= MAX_ROUTE_POINTS) return points
            var tolerance = 3.0
            var simple = points
            repeat(12) {
                simple = CardGeometry.simplify(points, tolerance)
                if (simple.size <= MAX_ROUTE_POINTS) return simple
                tolerance *= 2
            }
            val step = simple.size.toDouble() / MAX_ROUTE_POINTS
            return List(MAX_ROUTE_POINTS) { simple[(it * step).toInt()] }
        }
    }
}
