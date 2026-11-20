package app.roadmaxxing.shared.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.roadmaxxing.shared.data.SqlDriveStore
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.domain.DriveStatus
import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.map.layers.MapLayerPreferences
import app.roadmaxxing.shared.milestones.MilestoneRepository
import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.onboarding.SqlAppMetaStore
import app.roadmaxxing.shared.privacy.PrivacyZoneRepository
import app.roadmaxxing.shared.privacy.ZoneKind
import app.roadmaxxing.shared.settings.DistanceUnit
import app.roadmaxxing.shared.settings.SettingsRepository
import app.roadmaxxing.shared.summary.GeoPoint
import app.roadmaxxing.shared.testdata.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncStoreTest {

    private class Env(val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RoadmaxxingDatabase.Schema.create(it) }) {
        var now = 1_000_000L
        val db = RoadmaxxingDatabase(driver)
        val meta = SqlAppMetaStore(db)
        val driveStore = SqlDriveStore(db, Dispatchers.Unconfined)
        val zones = PrivacyZoneRepository(db, driveStore, meta, Dispatchers.Unconfined, clock = { now })
        val settings = SettingsRepository(meta)
        val layers = MapLayerPreferences(meta)
        val milestones = MilestoneRepository(
            driveStore, RegionPackProvider(TestData.packSource("test-data/regions/adana")), meta, Dispatchers.Unconfined,
        )
        val sync = SyncStore(db, driveStore, meta, zones, settings, milestones, layers, Dispatchers.Unconfined, clock = { now })

        /** A processed local drive that first drove [segments] at [startMs]. */
        fun localDrive(startMs: Long, segments: List<Long>, verdict: DriveVerdict = DriveVerdict.Drive, points: Boolean = true): Long {
            val id = driveStore.createDrive(startMs, "test")
            if (points) {
                driveStore.appendPoints(
                    id,
                    List(30) { TracePoint(startMs + it * 1000L, 37.0 + it * 0.0005, 35.3 + it * 0.0003, 10.0, null, 5.0) },
                )
            }
            driveStore.markEnded(id, startMs + 30_000, "test", startMs, startMs + 29_000)
            driveStore.completeProcessing(id, segments, startMs) {
                DriveSummary(verdict, 1000.0, 29_000, 30, 1.0, segments.size, it.size * 100L, 0, startMs + 40_000)
            }
            return id
        }

        fun segmentRows(): Map<Long, Pair<Long, Long>> = db.drivenSegmentQueries.selectAllWithFirstDrive().executeAsList()
            .associate { it.segment_id to it.first_drive_id }.mapValues { (id, drive) ->
                val ms = driver.executeQuery(null, "SELECT first_driven_ms FROM driven_segment WHERE segment_id = $id", { c ->
                    c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value
                ms to drive
            }
    }

    private fun remote(id: String, start: Long, segs: List<Long>, route: List<GeoPoint> = emptyList()) = RemoteDrive(
        id, start, start + 600_000, "Drive", 5000.0, 600_000, 10.0, 20.0, segs.size * 100L, route, segs,
    )

    @Test
    fun migrationFromVersion3KeepsRowsAndAddsSyncColumns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        for (sql in listOf(
            "CREATE TABLE app_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            """CREATE TABLE drive (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, start_reason TEXT NOT NULL,
                status TEXT NOT NULL, end_decision_ms INTEGER, end_reason TEXT, keep_from_ms INTEGER, keep_to_ms INTEGER, verdict TEXT,
                distance_m REAL, duration_ms INTEGER, point_count INTEGER, matched_point_fraction REAL, driven_segment_count INTEGER,
                new_length_dm INTEGER, processing_ms INTEGER, processed_at_ms INTEGER)""",
            """CREATE TABLE trace_point (drive_id INTEGER NOT NULL REFERENCES drive(id) ON DELETE CASCADE, time_ms INTEGER NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, speed_mps REAL, bearing_deg REAL, accuracy_m REAL, PRIMARY KEY (drive_id, time_ms))""",
            "CREATE TABLE driven_segment (segment_id INTEGER NOT NULL PRIMARY KEY, first_driven_ms INTEGER NOT NULL, first_drive_id INTEGER NOT NULL)",
            """CREATE TABLE privacy_zone (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, name TEXT, lat REAL NOT NULL, lon REAL NOT NULL,
                radius_m INTEGER NOT NULL, created_at INTEGER NOT NULL)""",
            "INSERT INTO app_meta(key, value) VALUES ('distance_unit', 'mi')",
            "INSERT INTO drive(start_ms, start_reason, status, verdict, distance_m, processed_at_ms) VALUES (1000, 'test', 'PROCESSED', 'Drive', 5.0, 2000)",
            "INSERT INTO drive(start_ms, start_reason, status) VALUES (5000, 'test', 'PENDING')",
            "INSERT INTO trace_point(drive_id, time_ms, lat, lon) VALUES (1, 1000, 37.0, 35.3)",
            "INSERT INTO driven_segment VALUES (77, 1000, 1)",
            "INSERT INTO privacy_zone(kind, lat, lon, radius_m, created_at) VALUES ('HOME', 37.0, 35.3, 300, 1)",
            "PRAGMA user_version = 3",
        )) driver.execute(null, sql, 0)

        RoadmaxxingDatabase.Schema.migrate(driver, 3, RoadmaxxingDatabase.Schema.version)
        assertEquals(4L, RoadmaxxingDatabase.Schema.version)

        val db = RoadmaxxingDatabase(driver)
        assertEquals("mi", db.appMetaQueries.selectValue("distance_unit").executeAsOne())
        val rows = db.driveQueries.selectAll().executeAsList()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.sync_id == null && it.synced_at_ms == null && it.remote_route == null })
        assertEquals(setOf(77L), db.drivenSegmentQueries.selectAllIds().executeAsList().toSet())
        assertEquals(1, db.privacyZoneQueries.selectAll().executeAsList().size)

        // New columns work, the unique index is enforced, and the sync layer runs on the migrated database.
        val env = Env(driver)
        runBlocking {
            env.sync.ensureSyncIds()
            val ids = env.sync.localSyncIds()
            assertEquals(1, ids.size) // only the PROCESSED drive gets an id
            assertEquals(1, env.sync.pendingCount())
        }
        val dup = runCatching { driver.execute(null, "UPDATE drive SET sync_id = (SELECT sync_id FROM drive WHERE id = 1) WHERE id = 2", 0) }
        assertTrue(dup.isFailure, "unique sync_id index must reject duplicates")
    }

    @Test
    fun syncIdsUnsyncedQueueAndMarking() = runBlocking {
        val env = Env()
        env.localDrive(1_000, listOf(1, 2, 3))
        env.localDrive(2_000, listOf(3, 4), verdict = DriveVerdict.TooShort)
        env.driveStore.createDrive(3_000, "recording") // not processed: never synced
        assertEquals(2, env.sync.pendingCount())
        assertTrue(env.sync.unsyncedDrives().isEmpty()) // no ids yet

        env.sync.ensureSyncIds()
        val ids = env.sync.localSyncIds()
        assertEquals(2, ids.size)
        env.sync.ensureSyncIds()
        assertEquals(ids, env.sync.localSyncIds()) // idempotent

        val pending = env.sync.unsyncedDrives()
        assertEquals(2, pending.size)
        val first = pending[0]
        assertEquals("Drive", first.verdict)
        assertEquals(listOf(1L, 2L, 3L), first.firstDrivenSegmentIds)
        assertTrue(first.route.size in 2..SyncStore.MAX_ROUTE_POINTS)
        assertEquals(1_000L + 29_000, first.endMs)
        assertEquals(emptyList(), pending[1].firstDrivenSegmentIds) // non-drive verdicts carry no route or segments
        assertEquals(emptyList(), pending[1].route)

        env.sync.markSynced(listOf(first.syncId), 9_999)
        assertEquals(1, env.sync.pendingCount())
        assertEquals(listOf(pending[1].syncId), env.sync.unsyncedDrives().map { it.syncId })

        env.sync.boundUid = "uid-1"
        assertEquals("uid-1", env.sync.boundUid)
        env.sync.clearSyncState()
        assertNull(env.sync.boundUid)
        assertEquals(2, env.sync.pendingCount())
        assertEquals(ids, env.sync.localSyncIds()) // ids stay
    }

    @Test
    fun importIntoEmptyDatabaseRebuildsSegmentsAndFlows() = runBlocking {
        val src = Env()
        src.localDrive(1_000, (1L..40L).toList())
        src.localDrive(5_000_000, (30L..80L).toList() + 4_000_000_000_000L)
        src.sync.ensureSyncIds()
        val uploaded = src.sync.unsyncedDrives().map { SyncCodec.decodeDrive(it.syncId, SyncCodec.encodeDrive(it))!! }

        val dst = Env()
        dst.sync.importDrives(uploaded.reversed()) // arrival order must not matter
        assertEquals(src.driveStore.drivenSegmentIds(), dst.driveStore.drivenSegmentIds())
        assertEquals(src.segmentRows(), dst.segmentRows())

        val list = dst.driveStore.observeDrives().first()
        assertEquals(2, list.size)
        assertTrue(list.all { it.status == DriveStatus.Processed && it.summary?.verdict == DriveVerdict.Drive })
        assertEquals(0, dst.sync.pendingCount()) // imported drives are marked synced
        assertEquals(src.sync.localSyncIds(), dst.sync.localSyncIds())
        // New length of the drive is preserved for stats.
        assertEquals(uploaded.map { it.newLengthDm }.toSet(), list.map { it.summary?.newLengthDm }.toSet())
    }

    @Test
    fun importKeepsEarliestFirstDriveAndIsIdempotent() = runBlocking {
        val env = Env()
        val local = env.localDrive(10_000, listOf(1, 2, 3, 4))   // local drive drove 1..4 at t=10 000
        env.sync.ensureSyncIds()
        val older = remote("older", 5_000, listOf(3, 4, 5))       // older drive: steals 3 and 4, adds 5
        val newer = remote("newer", 20_000, listOf(1, 6))          // newer drive: only adds 6
        env.sync.importDrives(listOf(newer, older))

        val rows = env.segmentRows()
        val olderId = env.db.driveQueries.selectAll().executeAsList().first { it.sync_id == "older" }.id
        val newerId = env.db.driveQueries.selectAll().executeAsList().first { it.sync_id == "newer" }.id
        assertEquals(10_000L to local, rows[1])
        assertEquals(10_000L to local, rows[2])
        assertEquals(5_000L to olderId, rows[3])
        assertEquals(5_000L to olderId, rows[4])
        assertEquals(5_000L to olderId, rows[5])
        assertEquals(20_000L to newerId, rows[6])
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), env.driveStore.drivenSegmentIds())
        assertEquals(setOf(1L, 2L), env.driveStore.segmentsFirstDrivenBy(local))

        val before = env.db.driveQueries.selectAll().executeAsList().size
        env.sync.importDrives(listOf(older, newer))
        assertEquals(before, env.db.driveQueries.selectAll().executeAsList().size)
        assertEquals(rows, env.segmentRows())
        // The local drive is still pending upload and only reports the segments it still owns.
        assertEquals(listOf(1L, 2L), env.sync.unsyncedDrives().single().firstDrivenSegmentIds)
    }

    @Test
    fun importedDriveExposesItsRouteAsSyntheticPoints() = runBlocking {
        val env = Env()
        val route = List(20) { GeoPoint(37.0 + it * 0.001, 35.3 + it * 0.001) }
        env.sync.importDrives(listOf(remote("r", 1_000_000, listOf(1), route)))
        val id = env.db.driveQueries.selectAll().executeAsList().single().id
        val record = env.driveStore.drive(id)!!
        val pts = env.driveStore.points(id, record.keepFromMs!!, record.keepToMs!!)
        assertEquals(20, pts.size)
        assertEquals(1_000_000L, pts.first().timeMs)
        assertEquals(1_600_000L, pts.last().timeMs)
        assertEquals(37.0, pts.first().lat, 1e-9)
        assertTrue(pts.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs })
        // A drive without route and without fixes has none.
        env.sync.importDrives(listOf(remote("empty", 9_000_000, listOf(2))))
        val emptyId = env.db.driveQueries.selectAll().executeAsList().first { it.sync_id == "empty" }.id
        assertTrue(env.driveStore.points(emptyId, Long.MIN_VALUE, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun profileStampsOnChangeAndAppliesLastWriteWins() = runBlocking {
        val env = Env()
        // Pristine profile: stamp 0 (any cloud profile wins), milestones alone do not count.
        env.meta.put("milestones_achieved", "p:1:5")
        assertEquals(0L, env.sync.localProfile(500).updatedAtMs)

        env.now = 2_000
        env.settings.setDistanceUnit(DistanceUnit.Miles)
        env.zones.add(ZoneKind.Home, "Home", 37.0, 35.3, 300)
        val p1 = env.sync.localProfile(2_000)
        assertEquals(2_000L, p1.updatedAtMs)
        assertEquals("mi", p1.settings["distance_unit"])
        assertEquals(listOf(RemoteZone("HOME", "Home", 37.0, 35.3, 300)), p1.zones)
        assertFalse("onboarding_completed" in p1.settings || "consent_version" in p1.settings)
        assertEquals(2_000L, env.sync.localProfile(9_000).updatedAtMs) // unchanged content keeps its stamp

        env.layers.setShowUndiscovered(false)
        assertEquals(10_000L, env.sync.localProfile(10_000).updatedAtMs)

        // An older remote profile is ignored (except merged milestones); a newer one replaces settings and zones.
        env.now = 11_000
        env.sync.applyProfile(RemoteProfile(5_000, mapOf("distance_unit" to "km", "milestones_achieved" to "d:7"), emptyList()))
        assertEquals(DistanceUnit.Miles, env.settings.distanceUnit.value)
        assertEquals(1, env.zones.zones.value.size)
        assertEquals("p:1:5,d:7", env.meta.get("milestones_achieved"))

        env.sync.applyProfile(
            RemoteProfile(
                50_000, mapOf("distance_unit" to "km", "quiet_hours" to "1|60|120"),
                listOf(RemoteZone("WORK", "Office", 36.9, 35.2, 250)),
            ),
        )
        assertEquals(DistanceUnit.Kilometers, env.settings.distanceUnit.value)
        assertTrue(env.settings.quietHours.value.enabled)
        assertTrue(env.layers.showUndiscovered.value) // key absent remotely -> default (on)
        assertEquals(listOf("Office"), env.zones.zones.value.map { it.name })
        assertEquals(ZoneKind.Work, env.zones.zones.value.single().kind)
        // Applying must not count as a local change: the remote stamp stays (milestones are merged, so those stay local
        // and differ from the remote document: only that re-stamps, which is what uploads the merged set).
        assertEquals(60_000L, env.sync.localProfile(60_000).updatedAtMs)
        env.meta.put("milestones_achieved", "")
        env.sync.applyProfile(RemoteProfile(70_000, mapOf("distance_unit" to "mi"), emptyList()))
        assertEquals(70_000L, env.sync.localProfile(80_000).updatedAtMs)
    }

    @Test
    fun cloudProfileWinsOverPristineDeviceButNotOverNewerLocalEdits() = runBlocking {
        val env = Env()
        env.meta.put("milestones_achieved", "") // silently seeded on a fresh install
        env.sync.applyProfile(RemoteProfile(1_000, mapOf("distance_unit" to "mi"), listOf(RemoteZone("HOME", null, 1.0, 2.0, 300))))
        assertEquals(DistanceUnit.Miles, env.settings.distanceUnit.value)
        assertEquals(1, env.zones.zones.value.size)

        env.now = 5_000
        env.settings.setDistanceUnit(DistanceUnit.Kilometers)
        env.sync.applyProfile(RemoteProfile(3_000, mapOf("distance_unit" to "mi"), emptyList())) // stale
        assertEquals(DistanceUnit.Kilometers, env.settings.distanceUnit.value)
    }
}
