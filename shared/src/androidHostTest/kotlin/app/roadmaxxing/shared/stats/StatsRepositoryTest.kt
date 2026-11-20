package app.roadmaxxing.shared.stats

import app.roadmaxxing.shared.testdata.PerfBudget
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.roadmaxxing.shared.data.SqlDriveStore
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.matching.RoadMatcher
import app.roadmaxxing.shared.summary.SpeedStats
import app.roadmaxxing.shared.testdata.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsRepositoryTest {
    private val istanbul = TimeZone.of("Europe/Istanbul")

    /** Wednesday 2025-01-08 12:00 in Istanbul; the current week starts Monday 2025-01-06. */
    private val now = LocalDateTime(2025, 1, 8, 12, 0).toInstant(istanbul).toEpochMilliseconds()
    private val currentMonday = LocalDate(2025, 1, 6).toEpochDays().toLong()

    private val packProvider by lazy { RegionPackProvider(TestData.packSource("test-data/regions/adana")) }

    private class Env(val store: SqlDriveStore, val repo: StatsRepository)

    private fun env(tz: TimeZone = istanbul, clock: Long = now): Env {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RoadmaxxingDatabase.Schema.create(it) }
        val store = SqlDriveStore(RoadmaxxingDatabase(driver), Dispatchers.Unconfined)
        return Env(store, StatsRepository(store, packProvider, Dispatchers.Default, clock = { clock }, timeZone = { tz }))
    }

    private fun localMs(y: Int, m: Int, d: Int, h: Int, min: Int, tz: TimeZone = istanbul) =
        LocalDateTime(y, m, d, h, min).toInstant(tz).toEpochMilliseconds()

    private fun Env.addDrive(
        startMs: Long,
        segments: Collection<Long> = emptyList(),
        newLengthDm: Long = 0,
        distanceM: Double = 1000.0,
        durationMs: Long = 100_000,
        verdict: DriveVerdict = DriveVerdict.Drive,
    ): Long {
        val id = store.createDrive(startMs, "test")
        store.markEnded(id, startMs + durationMs, "test", startMs, startMs + durationMs)
        store.completeProcessing(id, segments, startMs) {
            DriveSummary(verdict, distanceM, durationMs, 10, 1.0, segments.size, newLengthDm, 1, 1)
        }
        return id
    }

    private fun Env.latest(timeoutMs: Long = 20_000, predicate: (StatsSnapshot) -> Boolean = { true }): StatsSnapshot =
        runBlocking { withTimeout(timeoutMs) { repo.observe().first { it != null && predicate(it) }!! } }

    /** Segment ids grouped by way (id / 4096); only ways that exist in the pack. */
    private val waysWithSegments: Map<Long, List<Long>> by lazy {
        packProvider.pack.allSegmentIds().groupBy { it / 4096L }
    }

    @Test
    fun emptyDatabaseGivesZerosAndNulls() {
        val s = env().latest()
        assertEquals(8, s.weeks.size)
        assertTrue(s.weeks.all { it.newMeters == 0.0 })
        assertEquals(currentMonday, s.weeks.last().weekStartEpochDay)
        assertEquals(currentMonday - 49, s.weeks.first().weekStartEpochDay)
        assertEquals(0.0, s.provincePercent)
        assertEquals(0, s.streetsDriven)
        assertEquals(0, s.newStreetsThisWeek)
        assertEquals(0, s.driveCount)
        assertEquals(0.0, s.totalDistanceM)
        assertNull(s.avgSpeedMps)
        assertNull(s.maxSpeedMps)
        assertEquals(1.0, s.nextMilestone!!.targetPercent)
        assertEquals(0f, s.nextMilestone!!.progressFraction)
        assertTrue(s.districtsTotal > 0)
        assertEquals(0, s.districtsCompleted)
        assertEquals(s.districtsTotal, s.districts.size)
        assertNotNull(s.provinceName)
    }

    @Test
    fun weekBoundaryUsesLocalTimeZone() {
        val e = env()
        // Sunday 23:59 local is still last week; Monday 00:00 local starts the current one (in UTC it would be Sunday 21:00).
        e.addDrive(localMs(2025, 1, 5, 23, 59), newLengthDm = 1000)
        e.addDrive(localMs(2025, 1, 6, 0, 0), newLengthDm = 250)
        val s = e.latest { it.driveCount == 2 }
        assertEquals(100.0, s.weeks[6].newMeters)
        assertEquals(25.0, s.weeks[7].newMeters)
        assertEquals(25.0, s.newMetersThisWeek)
    }

    @Test
    fun weekBoundaryInAWesternZone() {
        val la = TimeZone.of("America/Los_Angeles")
        // Wednesday 2025-01-08 12:00 in LA -> week starts Monday 2025-01-06 local.
        val e = env(tz = la, clock = localMs(2025, 1, 8, 12, 0, la))
        e.addDrive(localMs(2025, 1, 5, 23, 59, la), newLengthDm = 500) // Sunday evening LA = Monday UTC
        e.addDrive(localMs(2025, 1, 6, 0, 0, la), newLengthDm = 100)
        val s = e.latest { it.driveCount == 2 }
        assertEquals(50.0, s.weeks[6].newMeters)
        assertEquals(10.0, s.weeks[7].newMeters)
    }

    @Test
    fun alwaysEightBarsAndOldDrivesFallOut() {
        val e = env()
        e.addDrive(localMs(2024, 6, 1, 10, 0), newLengthDm = 9999) // far older than 8 weeks
        e.addDrive(localMs(2024, 11, 20, 10, 0), newLengthDm = 40) // exactly first bar: Monday 2024-11-18 week
        val s = e.latest { it.driveCount == 2 }
        assertEquals(8, s.weeks.size)
        assertEquals(LocalDate(2024, 11, 18).toEpochDays().toLong(), s.weeks.first().weekStartEpochDay)
        assertEquals(4.0, s.weeks.first().newMeters)
        assertEquals(4.0, s.weeks.sumOf { it.newMeters })
        assertEquals(2, s.driveCount) // totals still count every drive
    }

    @Test
    fun streetsAreDistinctWaysAndNewOnesAreThisWeeks() {
        val multi = waysWithSegments.values.first { it.size >= 2 }
        val others = waysWithSegments.values.filter { it !== multi }.take(3).map { it.first() }
        val e = env()
        // Last week: one segment of the multi-segment way plus another way. This week: the way's other segment + 2 new ways.
        e.addDrive(localMs(2025, 1, 1, 10, 0), listOf(multi[0], others[0]))
        e.addDrive(localMs(2025, 1, 7, 10, 0), listOf(multi[1], others[1], others[2]))
        val s = e.latest { it.driveCount == 2 }
        assertEquals(4, s.streetsDriven) // multi way counted once
        assertEquals(2, s.newStreetsThisWeek) // the multi way was already driven last week
    }

    @Test
    fun milestoneMath() {
        val total = 1_000_000L // dm
        fun at(percent: Double) = StatsMath.nextMilestone((total * percent / 100.0).toLong(), total)!!
        val zero = at(0.0)
        assertEquals(1.0, zero.targetPercent)
        assertEquals(0f, zero.progressFraction)
        assertEquals(1_000.0, zero.remainingMeters, 1e-6)
        val mid = at(3.0)
        assertEquals(5.0, mid.targetPercent)
        assertEquals(0.5f, mid.progressFraction, 1e-4f)
        assertEquals(2_000.0, mid.remainingMeters, 1e-6) // 2 % of 1e6 dm = 2000 m
        val exact = at(5.0)
        assertEquals(10.0, exact.targetPercent)
        assertEquals(0f, exact.progressFraction, 1e-4f)
        assertEquals(5_000.0, exact.remainingMeters, 1e-6)
        assertNull(StatsMath.nextMilestone(total, total))
        assertNull(StatsMath.nextMilestone(0, 0))
    }

    @Test
    fun milestoneFromRealProgress() {
        val ids = packProvider.pack.allSegmentIds().toList().take(2000)
        val e = env()
        e.addDrive(localMs(2025, 1, 7, 10, 0), ids)
        val s = e.latest { it.driveCount == 1 }
        val m = assertNotNull(s.nextMilestone)
        assertTrue(m.targetPercent > s.provincePercent)
        assertTrue(m.remainingMeters > 0.0)
        assertTrue(m.progressFraction in 0f..1f)
    }

    @Test
    fun totalsIgnoreNotADrive() {
        val e = env()
        e.addDrive(localMs(2025, 1, 7, 10, 0), distanceM = 2000.0, durationMs = 200_000, newLengthDm = 100)
        e.addDrive(localMs(2025, 1, 7, 11, 0), distanceM = 3000.0, durationMs = 300_000, newLengthDm = 200)
        e.addDrive(localMs(2025, 1, 7, 12, 0), distanceM = 50_000.0, durationMs = 9_000_000, newLengthDm = 999_999, verdict = DriveVerdict.TooFast)
        e.addDrive(localMs(2025, 1, 7, 13, 0), distanceM = 100.0, durationMs = 10_000, verdict = DriveVerdict.TooShort)
        val s = e.latest { it.driveCount == 2 && it.avgSpeedMps != null }
        assertEquals(5000.0, s.totalDistanceM)
        assertEquals(500_000L, s.totalDurationMs)
        assertEquals(30.0, s.newMetersThisWeek)
        // No raw points stored: moving time falls back to the full duration -> 5000 m / 500 s.
        assertEquals(10.0, s.avgSpeedMps!!, 1e-9)
        assertNull(s.maxSpeedMps)
    }

    @Test
    fun speedsFromStoredPoints() {
        val trace = TestData.readGpx("main-roads.gpx")
        val expected = SpeedStats.compute(trace)
        val e = env()
        val id = e.store.createDrive(trace.first().timeMs, "test")
        e.store.appendPoints(id, trace)
        e.store.markEnded(id, trace.last().timeMs, "test", trace.first().timeMs, trace.last().timeMs)
        e.store.completeProcessing(id, emptyList(), trace.first().timeMs) {
            DriveSummary(DriveVerdict.Drive, 20_000.0, trace.last().timeMs - trace.first().timeMs, trace.size, 1.0, 0, 0, 1, 1)
        }
        val s = e.latest { it.maxSpeedMps != null }
        assertEquals(expected.maxMps!!, s.maxSpeedMps!!, 1e-9)
        assertEquals(20_000.0 / (20_000.0 / expected.avgMps!!), s.avgSpeedMps!!, 1e-6)
        assertTrue(s.avgSpeedMps!! <= s.maxSpeedMps!!)
    }

    @Test
    fun reEmitsAfterANewDrive() = runBlocking {
        val e = env()
        val ids = packProvider.pack.allSegmentIds().toList()
        e.addDrive(localMs(2025, 1, 7, 9, 0), ids.take(10), newLengthDm = 100)
        val flow = e.repo.observe()
        assertEquals(1, withTimeout(20_000) { flow.first { it != null && it.driveCount == 1 } }!!.driveCount)
        val collected = async(Dispatchers.Default) {
            withTimeout(20_000) { flow.first { it != null && it.driveCount == 2 } }
        }
        delay(300)
        e.addDrive(localMs(2025, 1, 7, 10, 0), ids.drop(10).take(10), newLengthDm = 200)
        val after = collected.await()!!
        assertEquals(30.0, after.newMetersThisWeek)
    }

    @Test
    fun coldEmitIsFastWithTheRealPack() {
        val pack = packProvider.pack
        val trace = TestData.readGpx("main-roads.gpx")
        val driven = RoadMatcher(pack).match(trace).driven.map { it.segmentId }
        val e = env()
        e.addDrive(localMs(2025, 1, 7, 9, 0), driven, newLengthDm = 5000)
        // Warm up the lazily built summary index like the app does on first open of the map; measure the stats emit itself.
        pack.allSegmentIds()
        val loadsBefore = pack.tileLoads
        val t0 = System.nanoTime()
        val s = e.latest { true }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("PERF stats cold emit: ${ms}ms, extra tile loads=${pack.tileLoads - loadsBefore}, segments=${driven.size}, streets=${s.streetsDriven}")
        assertTrue(ms < PerfBudget.ms(200L), "cold emit $ms ms")
        assertEquals(0, pack.tileLoads - loadsBefore, "stats must not decode tiles")
    }

    /** 60 s at a constant 100 m/s (360 km/h), no reported speed: impossible for a car. */
    private fun Env.addImpossibleDrive(startMs: Long): Long {
        val pts = (0 until 60).map { i ->
            app.roadmaxxing.shared.model.TracePoint(startMs + i * 1000L, 37.0 + i * 100.0 / 111_195.0, 35.3, null, null, null)
        }
        val id = store.createDrive(startMs, "test")
        store.appendPoints(id, pts)
        store.markEnded(id, pts.last().timeMs, "test", pts.first().timeMs, pts.last().timeMs)
        store.completeProcessing(id, emptyList(), startMs) {
            DriveSummary(DriveVerdict.Drive, 5_900.0, 59_000, pts.size, 1.0, 0, 0, 1, 1)
        }
        return id
    }

    @Test
    fun implausibleSpeedsAreIgnoredAlongsideARealDrive() {
        val trace = TestData.readGpx("main-roads.gpx")
        val expected = SpeedStats.compute(trace)
        val e = env()
        e.addImpossibleDrive(localMs(2025, 1, 7, 8, 0))
        val id = e.store.createDrive(trace.first().timeMs, "test")
        e.store.appendPoints(id, trace)
        e.store.markEnded(id, trace.last().timeMs, "test", trace.first().timeMs, trace.last().timeMs)
        e.store.completeProcessing(id, emptyList(), trace.first().timeMs) {
            DriveSummary(DriveVerdict.Drive, 20_000.0, trace.last().timeMs - trace.first().timeMs, trace.size, 1.0, 0, 0, 1, 1)
        }
        val s = e.latest { it.driveCount == 2 && it.maxSpeedMps != null }
        assertEquals(expected.maxMps!!, s.maxSpeedMps!!, 1e-9) // not the ~100 m/s of the bogus drive
        assertEquals(20_000.0 / (20_000.0 / expected.avgMps!!), s.avgSpeedMps!!, 1e-6) // bogus drive left out of the average
    }

    @Test
    fun onlyImplausibleDrivesGiveNoSpeeds() {
        val e = env()
        e.addImpossibleDrive(localMs(2025, 1, 7, 8, 0))
        val s = e.latest { it.driveCount == 1 }
        assertNull(s.avgSpeedMps)
        assertNull(s.maxSpeedMps)
        val withSpeed = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(1500) { e.repo.observe().first { it?.maxSpeedMps != null || it?.avgSpeedMps != null } }
        }
        assertNull(withSpeed)
    }
}
