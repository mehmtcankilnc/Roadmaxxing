package app.roadmaxxing.shared.privacy

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.domain.DriveRecord
import app.roadmaxxing.shared.domain.DriveStatus
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.onboarding.AppMetaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class ZoneKind { Home, Work, Custom }

data class PrivacyZone(val id: Long, val kind: ZoneKind, val name: String?, val lat: Double, val lon: Double, val radiusM: Int)

data class ZoneSuggestion(val lat: Double, val lon: Double, val driveCount: Int)

/**
 * Privacy zones (circles cut out of shared images) and the one-time "add a zone here?" suggestion.
 * Zone reads and writes are small synchronous SQLite calls; [suggestion] scans drives and runs on [dispatcher].
 */
class PrivacyZoneRepository(
    private val database: RoadmaxxingDatabase,
    private val store: DriveStore,
    private val appMeta: AppMetaStore,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val queries get() = database.privacyZoneQueries
    private val state = MutableStateFlow(load())

    val zones: StateFlow<List<PrivacyZone>> = state.asStateFlow()

    fun add(kind: ZoneKind, name: String?, lat: Double, lon: Double, radiusM: Int): Long {
        val id = database.transactionWithResult {
            queries.insertZone(kind.name.uppercase(), name, lat, lon, clampRadius(radiusM).toLong(), clock())
            queries.lastInsertedId().executeAsOne()
        }
        state.value = load()
        return id
    }

    fun update(zone: PrivacyZone) {
        queries.updateZone(zone.kind.name.uppercase(), zone.name, zone.lat, zone.lon, clampRadius(zone.radiusM).toLong(), zone.id)
        state.value = load()
    }

    /** Re-reads zones from the database (used after all data was erased). */
    fun reload() {
        state.value = load()
    }

    fun delete(id: Long) {
        queries.deleteZone(id)
        state.value = load()
    }

    /**
     * Centroid of the place where counted drives most often start or end, when there are at least
     * [MIN_DRIVES] counted drives, the best cluster has at least [MIN_HITS] hits, it lies outside every
     * zone and the suggestion was not dismissed. Null otherwise.
     */
    suspend fun suggestion(): ZoneSuggestion? = withContext(dispatcher) {
        if (appMeta.get(KEY_DISMISSED) == TRUE) return@withContext null
        val counted = store.observeDrives().first()
            .filter { it.status == DriveStatus.Processed && it.summary?.verdict == DriveVerdict.Drive }
        if (counted.size < MIN_DRIVES) return@withContext null

        // Read the live zones (not a snapshot taken earlier): points already covered by a zone never count.
        val zones = state.value
        val clusters = ArrayList<Cluster>()
        for (drive in counted.take(MAX_SCANNED_DRIVES)) {
            val ends = driveEnds(drive) ?: continue
            for ((lat, lon) in ends) {
                if (zones.any { Geo.distanceM(it.lat, it.lon, lat, lon) <= it.radiusM }) continue
                val c = clusters.firstOrNull { Geo.distanceM(it.lat, it.lon, lat, lon) <= CLUSTER_RADIUS_M }
                if (c == null) clusters.add(Cluster(lat, lon)) else c.add(lat, lon)
            }
        }
        // A cluster whose points could straddle a zone edge (centroid within radius + cluster radius) is covered too,
        // so leftovers of an already protected place are never suggested again.
        val best = clusters
            .filter { c -> zones.none { Geo.distanceM(it.lat, it.lon, c.lat, c.lon) <= it.radiusM + CLUSTER_RADIUS_M } }
            .maxByOrNull { it.hits } ?: return@withContext null
        if (best.hits < MIN_HITS) return@withContext null
        ZoneSuggestion(best.lat, best.lon, driveCount = best.hits)
    }

    fun dismissSuggestion() {
        appMeta.put(KEY_DISMISSED, TRUE)
    }

    private fun driveEnds(drive: DriveRecord): List<Pair<Double, Double>>? {
        val from = drive.keepFromMs ?: drive.startMs
        val to = drive.keepToMs ?: (from + (drive.summary?.durationMs ?: 0L))
        val pts = store.points(drive.id, from, to)
        if (pts.isEmpty()) return null
        val first = pts.minBy { it.timeMs }
        val last = pts.maxBy { it.timeMs }
        return listOf(first.lat to first.lon, last.lat to last.lon)
    }

    /** Running-mean cluster of start/end points. */
    private class Cluster(var lat: Double, var lon: Double) {
        var hits = 1
        fun add(la: Double, lo: Double) {
            lat += (la - lat) / (hits + 1)
            lon += (lo - lon) / (hits + 1)
            hits++
        }
    }

    private fun load(): List<PrivacyZone> = queries.selectAll { id, kind, name, lat, lon, radius ->
        PrivacyZone(
            id = id,
            kind = ZoneKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: ZoneKind.Custom,
            name = name, lat = lat, lon = lon, radiusM = radius.toInt(),
        )
    }.executeAsList()

    private fun clampRadius(r: Int) = r.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)

    companion object {
        const val MIN_RADIUS_M = 200
        const val MAX_RADIUS_M = 1000
        const val DEFAULT_RADIUS_M = 300
        private const val MIN_DRIVES = 5
        private const val MIN_HITS = 3
        private const val CLUSTER_RADIUS_M = 150.0
        private const val MAX_SCANNED_DRIVES = 200
        private const val KEY_DISMISSED = "privacy_suggestion_dismissed"
        private const val TRUE = "1"
    }
}
