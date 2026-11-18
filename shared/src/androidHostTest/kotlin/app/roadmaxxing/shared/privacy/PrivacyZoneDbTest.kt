package app.roadmaxxing.shared.privacy

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.domain.InMemoryDriveStore
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.onboarding.AppMetaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivacyZoneDbTest {

    private class MapMeta : AppMetaStore {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    private fun freshDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RoadmaxxingDatabase.Schema.create(it) }

    private fun repo(driver: SqlDriver, store: InMemoryDriveStore = InMemoryDriveStore(), meta: AppMetaStore = MapMeta()) =
        PrivacyZoneRepository(RoadmaxxingDatabase(driver), store, meta, Dispatchers.Unconfined, clock = { 42L })

    @Test
    fun migrationFromVersion2KeepsOldDataAndAddsTheTable() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // The shipped version 2 schema (1.sqm), created by hand, with data.
        for (sql in listOf(
            "CREATE TABLE app_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
            """CREATE TABLE drive (id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, start_reason TEXT NOT NULL,
                status TEXT NOT NULL, end_decision_ms INTEGER, end_reason TEXT, keep_from_ms INTEGER, keep_to_ms INTEGER, verdict TEXT,
                distance_m REAL, duration_ms INTEGER, point_count INTEGER, matched_point_fraction REAL, driven_segment_count INTEGER,
                new_length_dm INTEGER, processing_ms INTEGER, processed_at_ms INTEGER)""",
            """CREATE TABLE trace_point (drive_id INTEGER NOT NULL REFERENCES drive(id) ON DELETE CASCADE, time_ms INTEGER NOT NULL,
                lat REAL NOT NULL, lon REAL NOT NULL, speed_mps REAL, bearing_deg REAL, accuracy_m REAL, PRIMARY KEY (drive_id, time_ms))""",
            "CREATE TABLE driven_segment (segment_id INTEGER NOT NULL PRIMARY KEY, first_driven_ms INTEGER NOT NULL, first_drive_id INTEGER NOT NULL)",
            "INSERT INTO app_meta(key, value) VALUES ('onboarding_completed', '1')",
            "INSERT INTO drive(start_ms, start_reason, status) VALUES (1000, 'test', 'RECORDING')",
            "INSERT INTO trace_point(drive_id, time_ms, lat, lon) VALUES (1, 1000, 37.0, 35.3)",
            "INSERT INTO driven_segment VALUES (77, 1000, 1)",
            "PRAGMA user_version = 2",
        )) driver.execute(null, sql, 0)

        RoadmaxxingDatabase.Schema.migrate(driver, 2, RoadmaxxingDatabase.Schema.version)
        assertTrue(RoadmaxxingDatabase.Schema.version >= 3)

        val db = RoadmaxxingDatabase(driver)
        assertEquals("1", db.appMetaQueries.selectValue("onboarding_completed").executeAsOne())
        assertEquals(1, db.driveQueries.selectAll().executeAsList().size)
        assertEquals(37.0, driver.executeQuery(null, "SELECT lat FROM trace_point", { c ->
            c.next(); QueryResult.Value(c.getDouble(0)) }, 0).value)
        assertEquals(77L, driver.executeQuery(null, "SELECT segment_id FROM driven_segment", { c ->
            c.next(); QueryResult.Value(c.getLong(0)) }, 0).value)

        val zones = repo(driver)
        assertTrue(zones.zones.value.isEmpty())
        val id = zones.add(ZoneKind.Home, "Home", 37.0, 35.3, 300)
        assertEquals(listOf(PrivacyZone(id, ZoneKind.Home, "Home", 37.0, 35.3, 300)), zones.zones.value)
    }

    @Test
    fun addUpdateDeleteClampAndPersist() {
        val driver = freshDriver()
        val r = repo(driver)
        val a = r.add(ZoneKind.Home, null, 37.0, 35.3, 50)
        val b = r.add(ZoneKind.Work, "Office", 37.1, 35.4, 5000)
        assertTrue(b > a)
        assertEquals(PrivacyZoneRepository.MIN_RADIUS_M, r.zones.value[0].radiusM)
        assertEquals(PrivacyZoneRepository.MAX_RADIUS_M, r.zones.value[1].radiusM)
        r.update(r.zones.value[0].copy(kind = ZoneKind.Custom, name = "Gym", radiusM = 450))
        assertEquals(PrivacyZone(a, ZoneKind.Custom, "Gym", 37.0, 35.3, 450), r.zones.value[0])
        r.update(r.zones.value[0].copy(radiusM = 99_999))
        assertEquals(PrivacyZoneRepository.MAX_RADIUS_M, r.zones.value[0].radiusM)
        // A new repository on the same database reads the stored zones synchronously.
        assertEquals(r.zones.value, repo(driver).zones.value)
        r.delete(a)
        assertEquals(listOf(b), r.zones.value.map { it.id })
    }

    private fun countedDrive(store: InMemoryDriveStore, startLat: Double, startLon: Double, endLat: Double, endLon: Double, n: Int) {
        val id = store.createDrive(n * 100_000L, "t")
        val t0 = n * 100_000L
        store.appendPoints(id, listOf(
            TracePoint(t0, startLat, startLon, null, null, null),
            TracePoint(t0 + 60_000, endLat, endLon, null, null, null),
        ))
        store.markEnded(id, t0 + 60_000, "t", t0, t0 + 60_000)
        store.completeProcessing(id, emptyList(), t0) { DriveSummary(DriveVerdict.Drive, 5000.0, 60_000, 2, 1.0, 0, 0, 0, 0) }
    }

    @Test
    fun suggestionRule() {
        val store = InMemoryDriveStore()
        val meta = MapMeta()
        val r = repo(freshDriver(), store, meta)
        // Home near (37.00, 35.30) with small jitter; the other ends are scattered.
        for (n in 0 until 4) countedDrive(store, 37.0 + n * 0.0003, 35.3, 37.1 + n * 0.05, 35.4 + n * 0.05, n)
        assertNull(runBlocking { r.suggestion() }, "fewer than 5 drives")
        countedDrive(store, 37.0, 35.3005, 37.5, 35.9, 4)
        val s = assertNotNull(runBlocking { r.suggestion() })
        assertEquals(37.0, s.lat, 0.002)
        assertEquals(35.3, s.lon, 0.002)
        assertEquals(5, s.driveCount)

        // Inside an existing zone: no suggestion.
        val zoneId = r.add(ZoneKind.Home, null, 37.0, 35.3, 300)
        assertNull(runBlocking { r.suggestion() })
        r.delete(zoneId)
        assertNotNull(runBlocking { r.suggestion() })

        r.dismissSuggestion()
        assertNull(runBlocking { r.suggestion() })
        assertEquals("1", meta.map["privacy_suggestion_dismissed"])
    }

    @Test
    fun noClusterWithThreeHitsGivesNoSuggestion() {
        val store = InMemoryDriveStore()
        val r = repo(freshDriver(), store)
        for (n in 0 until 5) countedDrive(store, 37.0 + n * 0.05, 35.3, 38.0 + n * 0.05, 36.0, n)
        assertNull(runBlocking { r.suggestion() })
    }

    @Test
    fun coveredPlaceIsNeverSuggestedAgain() {
        // Start points spread over ~400 m, so greedy clustering splits them into several clusters.
        for (zoneOffsetM in listOf(0.0, 250.0)) {
            val store = InMemoryDriveStore()
            val r = repo(freshDriver(), store)
            for (n in 0 until 6) countedDrive(store, 37.0 + n * 0.0007, 35.3, 37.0 + n * 0.0007, 35.3, n)
            val s = assertNotNull(runBlocking { r.suggestion() })
            val dLat = zoneOffsetM / 111_000.0
            r.add(ZoneKind.Home, null, s.lat + dLat, s.lon, 300)
            assertNull(runBlocking { r.suggestion() }, "offset $zoneOffsetM m")
        }
    }
}
