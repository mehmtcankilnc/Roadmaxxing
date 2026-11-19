package app.roadmaxxing.shared.milestones

import app.roadmaxxing.shared.onboarding.AppMetaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MilestoneRepositoryTest {
    private class MemoryMeta : AppMetaStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    /** Province 1 with total 10_000 dm and districts 10 (total 1_000) and 11 (total 1_000). */
    private class FakeProgress : MilestoneProgressSource {
        var province = 0L
        var d10 = 0L
        var d11 = 0L
        /** Progress as it was before drive [beforeDrive]; used for the first-run seed. */
        var beforeDrive: Triple<Long, Long, Long>? = null
        var beforeDriveId: Long? = null

        override fun state(excludeDriveId: Long?): ProgressState {
            val use = if (excludeDriveId != null && excludeDriveId == beforeDriveId) beforeDrive else null
            val (p, a, b) = use ?: Triple(province, d10, d11)
            return ProgressState(
                RegionLengths(1, "Adana", p, 10_000),
                listOf(RegionLengths(10, "Seyhan", a, 1_000), RegionLengths(11, "Cukurova", b, 1_000)),
            )
        }
    }

    private val names = mapOf(1L to "Adana", 10L to "Seyhan", 11L to "Cukurova")

    private fun repo(progress: FakeProgress, meta: MemoryMeta) = MilestoneRepository(
        progress = progress,
        appMeta = meta,
        dispatcher = Dispatchers.Unconfined,
        names = { names[it] },
        triggers = null,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    @Test
    fun firstRunSeedsSilently() = runTest {
        val p = FakeProgress().apply { province = 600; d10 = 1_000 } // 6 % and a completed district already
        val meta = MemoryMeta()
        val r = repo(p, meta)
        assertTrue(r.evaluate(null).isEmpty())
        assertNull(r.pending.value)
        assertTrue(meta.map["milestones_achieved"]!!.contains("p:1:5"))
        // Still silent afterwards for the same state.
        assertTrue(r.evaluate(1).isEmpty())
        assertNull(r.pending.value)
    }

    @Test
    fun crossingOnePercentQueuesOneCelebration() = runTest {
        val p = FakeProgress()
        val r = repo(p, MemoryMeta())
        r.evaluate(null) // seed at zero
        p.province = 100 // exactly 1 %
        val events = r.evaluate(7)
        assertEquals(listOf("p:1:1"), events.map { it.key })
        val c = assertNotNull(r.pending.value)
        assertEquals(7L, c.driveId)
        assertEquals(1, c.province?.percent)
        assertEquals("Adana", c.province?.name)
        assertTrue(c.districts.isEmpty())
    }

    @Test
    fun justBelowThresholdDoesNotFire() = runTest {
        val p = FakeProgress()
        val r = repo(p, MemoryMeta())
        r.evaluate(null)
        p.province = 99
        assertTrue(r.evaluate(1).isEmpty())
        assertNull(r.pending.value)
    }

    @Test
    fun oneAndFivePercentInOneDriveShowsFiveOnly() = runTest {
        val p = FakeProgress()
        val r = repo(p, MemoryMeta())
        r.evaluate(null)
        p.province = 520
        val events = r.evaluate(3)
        assertEquals(setOf("p:1:1", "p:1:5"), events.map { it.key }.toSet())
        assertEquals(5, r.pending.value?.province?.percent)
    }

    @Test
    fun districtCompletionAtNinetyFivePercent() = runTest {
        val p = FakeProgress()
        val r = repo(p, MemoryMeta())
        r.evaluate(null)
        p.d10 = 949
        assertTrue(r.evaluate(2).isEmpty())
        p.d10 = 950
        p.d11 = 1_000
        val events = r.evaluate(2)
        assertEquals(setOf("d:10", "d:11"), events.map { it.key }.toSet())
        val c = r.pending.value!!
        assertNull(c.province)
        assertEquals(listOf("Seyhan", "Cukurova"), c.districts.map { it.name })
    }

    @Test
    fun sameStateTwiceQueuesNothingNew() = runTest {
        val p = FakeProgress()
        val r = repo(p, MemoryMeta())
        r.evaluate(null)
        p.province = 600
        assertEquals(2, r.evaluate(4).size)
        assertTrue(r.evaluate(4).isEmpty())
        r.markCelebrated(r.pending.value!!)
        assertNull(r.pending.value)
    }

    @Test
    fun markCelebratedRemovesAndItStaysGone() = runTest {
        val p = FakeProgress()
        val meta = MemoryMeta()
        val r = repo(p, meta)
        r.evaluate(null)
        p.province = 150
        r.evaluate(1)
        p.province = 600
        r.evaluate(2)
        val first = r.pending.value!!
        assertEquals(1L, first.driveId) // oldest first
        r.markCelebrated(first)
        assertEquals(2L, r.pending.value?.driveId)
        assertTrue(r.evaluate(2).isEmpty())
        assertEquals(2L, r.pending.value?.driveId)
        // A new instance on the same storage sees only the remaining one.
        assertEquals(2L, repo(p, meta).pending.value?.driveId)
    }

    @Test
    fun persistsAcrossRepositoryInstances() = runTest {
        val p = FakeProgress()
        val meta = MemoryMeta()
        val r1 = repo(p, meta)
        r1.evaluate(null)
        p.province = 600
        p.d10 = 1_000
        r1.evaluate(9)
        val r2 = repo(p, meta)
        val c = assertNotNull(r2.pending.value)
        assertEquals(9L, c.driveId)
        assertEquals(5, c.province?.percent)
        assertEquals(listOf("Seyhan"), c.districts.map { it.name })
        assertTrue(r2.evaluate(9).isEmpty()) // already achieved
    }

    @Test
    fun reloadAfterEraseSeedsSilentlyAgain() = runTest {
        val p = FakeProgress()
        val meta = MemoryMeta()
        val r = repo(p, meta)
        r.evaluate(null)
        p.province = 600
        r.evaluate(1)
        assertNotNull(r.pending.value)
        // Simulate DataEraser: app_meta wiped, progress gone, then reload().
        meta.map.clear()
        p.province = 0
        r.reload()
        assertNull(r.pending.value)
        assertTrue(r.evaluate(null).isEmpty())
        p.province = 600 // the user drives again: celebrate afresh
        assertEquals(2, r.evaluate(5).size)
        assertEquals(5L, r.pending.value?.driveId)
    }

    @Test
    fun firstRunCalledForADriveCelebratesThatDrivesOwnMilestones() = runTest {
        val p = FakeProgress().apply {
            province = 600
            beforeDrive = Triple(0, 0, 0)
            beforeDriveId = 8
        }
        val r = repo(p, MemoryMeta())
        val events = r.evaluate(8) // repository created only after the drive was processed
        assertEquals(2, events.size)
        assertEquals(8L, r.pending.value?.driveId)
    }

    @Test
    fun thresholdListMatchesOwnerDecision() {
        assertEquals(listOf(1, 5, 10, 25, 50, 75, 90, 100), MilestoneRepository.PROVINCE_THRESHOLDS)
    }
}
