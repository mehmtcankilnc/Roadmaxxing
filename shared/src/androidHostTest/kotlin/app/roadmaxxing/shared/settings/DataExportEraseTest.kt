package app.roadmaxxing.shared.settings

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.roadmaxxing.shared.data.SqlDriveStore
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.map.DrivenRoadsProvider
import app.roadmaxxing.shared.map.layers.MapLayerPreferences
import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.onboarding.OnboardingRepository
import app.roadmaxxing.shared.onboarding.SqlAppMetaStore
import app.roadmaxxing.shared.privacy.PrivacyZoneRepository
import app.roadmaxxing.shared.privacy.ZoneKind
import app.roadmaxxing.shared.region.FixturePack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DataExportEraseTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RoadmaxxingDatabase.Schema.create(it) }
    private val db = RoadmaxxingDatabase(driver)
    private val store = SqlDriveStore(db, Dispatchers.Unconfined)
    private val meta = SqlAppMetaStore(db)

    private fun summary(at: Long, verdict: DriveVerdict, points: Int) =
        DriveSummary(verdict, 12_340.0, 600_000, points, 1.0, 2, 50_000, 5, at)

    private fun addDrive(start: Long, points: Int, verdict: DriveVerdict, segments: List<Long> = emptyList()): Long {
        val id = store.createDrive(start, "test")
        store.appendPoints(id, List(points) { i ->
            TracePoint(start + i * 1000L, 37.0 + i * 1e-4, 35.3 + i * 1e-4, 10.0, null, 5.0)
        })
        store.markEnded(id, start + points * 1000L, "stopped", start, start + points * 1000L)
        store.completeProcessing(id, segments, start) { summary(start, verdict, points) }
        return id
    }

    private fun exporter() = DataExporter(store, Dispatchers.Unconfined)

    private fun count(table: String): Long = driver.executeQuery(null, "SELECT count(*) FROM $table", { c ->
        c.next()
        QueryResult.Value(c.getLong(0)!!)
    }, 0).value

    @Test
    fun exportIsNullWithoutDrives() = runBlocking {
        var called = false
        assertNull(exporter().exportGpx(0) { called = true })
        assertTrue(!called)
    }

    /** Runs the export and returns the summary plus the copied chunks. */
    private suspend fun export(nowMs: Long): Pair<ExportSummary, List<String>> {
        val chunks = ArrayList<String>()
        val summary = assertNotNull(exporter().exportGpx(nowMs) { chunks.add(it.toString()) })
        return summary to chunks
    }

    @Test
    fun exportParsesBackWithAllDrivesAndPoints() = runBlocking {
        val t0 = 1_700_000_000_000L
        addDrive(t0, 30, DriveVerdict.Drive)
        addDrive(t0 + 100_000_000, 12, DriveVerdict.TooShort)
        val (file, chunks) = export(1_731_000_000_000L)
        assertEquals("roadmaxxing-export-2024-11-07.gpx", file.fileName)
        assertEquals(2, file.driveCount)
        assertEquals(4, chunks.size) // header, 2 drives, footer

        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(chunks.joinToString("").byteInputStream())
        val gpx = "http://www.topografix.com/GPX/1/1"
        assertEquals(2, doc.getElementsByTagNameNS(gpx, "trk").length)
        val pts = doc.getElementsByTagNameNS(gpx, "trkpt")
        assertEquals(42, pts.length)
        val first = pts.item(0)
        assertEquals(37.0, first.attributes.getNamedItem("lat").nodeValue.toDouble(), 1e-9)
        assertEquals(t0, Instant.parse(first.firstChild.textContent).toEpochMilliseconds())
        val descs = doc.getElementsByTagNameNS(gpx, "desc")
        assertTrue(descs.item(0).textContent.contains("verdict: Drive; distance: 12.34 km; new roads: 5.00 km"))
        assertTrue(descs.item(1).textContent.contains("TooShort"))
    }

    @Test
    fun oneHourDriveSize() = runBlocking {
        addDrive(1_700_000_000_000L, 3600, DriveVerdict.Drive)
        val (_, chunks) = export(1_700_000_000_000L)
        val total = chunks.sumOf { it.length }
        println("GPX size of a 1-hour drive (3600 points): $total chars in ${chunks.size} chunks")
        assertTrue(total < 400_000)
        assertTrue(chunks.size >= 4) // header, 2 point chunks, footer
    }

    @Test
    fun chunksStayBoundedAndStreamedExportIsValid() = runBlocking {
        for (d in 0 until 5) addDrive(1_700_000_000_000L + d * 10_000_000L, 5_000, DriveVerdict.Drive)
        val (summary, chunks) = export(1_700_000_000_000L)
        assertEquals(5, summary.driveCount)
        assertTrue(chunks.maxOf { it.length } <= 1_000_000, "largest chunk ${chunks.maxOf { it.length }}")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(chunks.joinToString("").byteInputStream())
        assertEquals(25_000, doc.getElementsByTagName("trkpt").length)
        assertEquals(5, doc.getElementsByTagName("trk").length)
    }

    @Test
    fun eraseWipesEverythingAndResetsRepositories() = runBlocking {
        val packProvider = RegionPackProvider(FixturePack.source())
        val ids = packProvider.pack.allSegmentIds().toList().take(3)
        addDrive(1_000_000L, 10, DriveVerdict.Drive, ids)
        val onboarding = OnboardingRepository(meta).also { it.markCompleted(); it.recordConsent(5L) }
        val zones = PrivacyZoneRepository(db, store, meta, Dispatchers.Unconfined).also {
            it.add(ZoneKind.Home, "h", 37.0, 35.3, 300)
            it.dismissSuggestion()
        }
        val layers = MapLayerPreferences(meta).also { it.setShowUndiscovered(false); it.setShowPrivacyZones(false) }
        val settings = SettingsRepository(meta).also {
            it.setDistanceUnit(DistanceUnit.Miles)
            it.setQuietHours(QuietHours(true, 1, 2))
        }
        meta.put("burn_shown_drive_id", "1")
        meta.put("schema_version", "9")
        val driven = DrivenRoadsProvider(packProvider, store, Dispatchers.Unconfined)
        assertEquals(3, driven.observe().first().segmentCount)
        assertTrue(driven.cachedSegmentCount > 0)

        DataEraser(db, onboarding, zones, layers, settings, driven, Dispatchers.Unconfined).eraseAll()

        for (t in listOf("drive", "trace_point", "driven_segment", "privacy_zone")) assertEquals(0L, count(t), t)
        assertEquals(1L, count("app_meta"))
        assertEquals("9", meta.get("schema_version"))

        assertEquals(false, onboarding.isCompleted.value)
        assertNull(onboarding.consent())
        assertTrue(zones.zones.value.isEmpty())
        assertEquals(true, layers.showUndiscovered.value)
        assertEquals(true, layers.showPrivacyZones.value)
        assertEquals(DistanceUnit.Kilometers, settings.distanceUnit.value)
        assertEquals(QuietHours.Default, settings.quietHours.value)
        assertEquals(0, driven.cachedSegmentCount)
        assertEquals(0, driven.observe().first().segmentCount)
        assertTrue(store.observeDrives().first().isEmpty())
        assertEquals(0L, store.observeDrivenSegmentCount().first())

        // The app can record again afterwards.
        val id = store.createDrive(5L, "after")
        store.appendPoints(id, listOf(TracePoint(5L, 37.0, 35.3, null, null, null)))
        assertEquals(1, store.observeDrives().first().size)
        onboarding.markCompleted()
        assertTrue(OnboardingRepository(meta).isCompleted.value)
    }
}
