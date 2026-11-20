package app.roadmaxxing.shared.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.roadmaxxing.shared.account.AccountRepository
import app.roadmaxxing.shared.account.AccountUser
import app.roadmaxxing.shared.account.AuthBackend
import app.roadmaxxing.shared.account.AuthProvider
import app.roadmaxxing.shared.account.AuthResult
import app.roadmaxxing.shared.account.AuthState
import app.roadmaxxing.shared.data.SqlDriveStore
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.map.DrivenRoadsProvider
import app.roadmaxxing.shared.map.burn.BurnRepository
import app.roadmaxxing.shared.map.layers.MapLayerPreferences
import app.roadmaxxing.shared.milestones.MilestoneRepository
import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.onboarding.OnboardingRepository
import app.roadmaxxing.shared.onboarding.SqlAppMetaStore
import app.roadmaxxing.shared.privacy.PrivacyZoneRepository
import app.roadmaxxing.shared.region.FixturePack
import app.roadmaxxing.shared.settings.DataEraser
import app.roadmaxxing.shared.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncManagerTest {
    private val userA = AccountUser("uidA", "a@example.com", null, AuthProvider.Email)
    private val userB = AccountUser("uidB", "b@example.com", null, AuthProvider.Email)

    /** In-memory Firestore: documents per uid. Failures are simulated with [failing]. */
    private class FakeRemote : SyncRemote {
        val drives = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
        val profiles = mutableMapOf<String, Map<String, Any?>>()
        var failing = false
        var failWith: Throwable? = null
        var listCalls = 0
        var putDriveCalls = 0
        var putProfileCalls = 0
        var deleted = 0

        private fun check() {
            failWith?.let { throw it }
            if (failing) throw IllegalStateException("network unavailable")
        }

        override suspend fun listDriveIds(uid: String): Set<String> {
            check()
            listCalls++
            return drives[uid].orEmpty().keys.toSet()
        }

        override suspend fun fetchDrives(uid: String, ids: Collection<String>): List<Map<String, Any?>> {
            check()
            return ids.mapNotNull { id -> drives[uid]?.get(id)?.plus("syncId" to id) }
        }

        override suspend fun putDrive(uid: String, syncId: String, doc: Map<String, Any?>) {
            check()
            putDriveCalls++
            drives.getOrPut(uid) { mutableMapOf() }[syncId] = doc
        }

        override suspend fun fetchProfile(uid: String): Map<String, Any?>? {
            check()
            return profiles[uid]
        }

        override suspend fun putProfile(uid: String, doc: Map<String, Any?>) {
            check()
            putProfileCalls++
            profiles[uid] = doc
        }

        override suspend fun deleteAll(uid: String) {
            check()
            deleted++
            drives.remove(uid)
            profiles.remove(uid)
        }
    }

    private class FakeBackend : AuthBackend {
        val flow = MutableStateFlow<AuthState>(AuthState.SignedOut)
        override val state get() = flow
        override suspend fun signUpWithEmail(email: String, password: String): AuthResult = AuthResult.Success
        override suspend fun signInWithEmail(email: String, password: String): AuthResult = AuthResult.Success
        override suspend fun sendPasswordReset(email: String): AuthResult = AuthResult.Success
        override suspend fun signInWithGoogle(): AuthResult = AuthResult.Success
        override suspend fun signOut() { flow.value = AuthState.SignedOut }
        override suspend fun deleteAccount(): AuthResult = AuthResult.Success
    }

    /** One phone: its own database, stores and sync manager. */
    private class Phone(val remote: FakeRemote, dispatcher: CoroutineDispatcher, now: () -> Long) {
        val db = RoadmaxxingDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RoadmaxxingDatabase.Schema.create(it) })
        val drives = SqlDriveStore(db, dispatcher)
        val meta = SqlAppMetaStore(db)
        val backend = FakeBackend()
        val syncStore: SyncStore
        val manager: SyncManager
        val milestones: MilestoneRepository
        var nextStart = 1_000_000L
        private var nextSegment = 1L

        init {
            val packProvider = RegionPackProvider(FixturePack.source())
            val zones = PrivacyZoneRepository(db, drives, meta, dispatcher)
            val settings = SettingsRepository(meta)
            val milestoneRepo = MilestoneRepository(drives, packProvider, meta, dispatcher)
            milestones = milestoneRepo
            val layers = MapLayerPreferences(meta)
            val eraser = DataEraser(
                db, OnboardingRepository(meta), zones, layers, settings,
                DrivenRoadsProvider(packProvider, drives, dispatcher), dispatcher,
            )
            syncStore = SyncStore(
                database = db, store = drives, appMeta = meta, zones = zones, settings = settings,
                milestones = milestoneRepo, layers = layers,
                dispatcher = dispatcher,
            )
            val account = AccountRepository(backend, drives, meta, eraser, dispatcher = dispatcher, syncRemote = remote)
            manager = SyncManager(
                syncStore, remote, account, eraser, drives, now, dispatcher,
                milestones = milestoneRepo, burn = BurnRepository(drives, packProvider, meta, dispatcher),
            )
        }

        fun addDrive(segments: Int = 2) {
            val start = nextStart
            nextStart += 3_600_000L
            val id = drives.createDrive(start, "test")
            drives.appendPoints(id, List(5) { i -> TracePoint(start + i * 1000L, 37.0, 35.3, 10.0, null, 5.0) })
            drives.markEnded(id, start + 5000L, "stopped", start, start + 5000L)
            val ids = List(segments) { nextSegment++ }
            drives.completeProcessing(id, ids, start) {
                DriveSummary(DriveVerdict.Drive, 5000.0, 600_000, 5, 1.0, 0, 0, 5, start)
            }
        }

        fun signIn(user: AccountUser) { backend.flow.value = AuthState.SignedIn(user) }
        fun signOut() { backend.flow.value = AuthState.SignedOut }
        fun driveCount() = runBlocking { drives.observeDrives().first().size }
    }

    private fun TestScope.phone(remote: FakeRemote, clock: () -> Long = { testScheduler.currentTime + 10_000_000L }) =
        Phone(remote, UnconfinedTestDispatcher(testScheduler), clock)

    @Test
    fun firstSignInUploadsGuestDrives() = runTest {
        val remote = FakeRemote()
        val p = phone(remote)
        p.addDrive()
        p.addDrive()
        assertEquals(SyncStatus.Disabled, p.manager.status.value)
        p.signIn(userA)
        runCurrent()
        assertEquals(2, remote.drives[userA.uid]?.size)
        assertEquals(1, remote.putProfileCalls)
        val status = p.manager.status.value
        assertIs<SyncStatus.Idle>(status)
        assertEquals(0, status.pending)
        assertNotNull(status.lastSyncedAtMs)
    }

    @Test
    fun noDuplicateUploads() = runTest {
        val remote = FakeRemote()
        val p = phone(remote)
        p.addDrive()
        p.signIn(userA)
        runCurrent()
        p.manager.syncNow()
        p.manager.onAppForeground()
        runCurrent()
        assertEquals(1, remote.putDriveCalls)
        assertEquals(1, remote.putProfileCalls)
    }

    @Test
    fun secondDeviceDownloadsDrivesAndSegments() = runTest {
        val remote = FakeRemote()
        val a = phone(remote)
        a.addDrive(3)
        a.addDrive(2)
        a.signIn(userA)
        runCurrent()
        val b = phone(remote)
        b.signIn(userA)
        runCurrent()
        assertEquals(2, b.driveCount())
        assertEquals(a.drives.drivenSegmentIds(), b.drives.drivenSegmentIds())
        assertEquals(5, b.drives.drivenSegmentIds().size)
        // The downloaded drives are already synced: nothing is uploaded back.
        assertEquals(2, remote.putDriveCalls)
        assertEquals(0, (b.manager.status.value as SyncStatus.Idle).pending)
    }

    @Test
    fun restoredDriveIsSilentButLocalDriveKeepsItsBurn() = runTest {
        val remote = FakeRemote()
        val a = phone(remote)
        a.addDrive(3)
        a.signIn(userA)
        runCurrent()
        // The phone that recorded the drive does not mark its own burn as shown.
        assertNull(a.meta.get("burn_shown_drive_id"))
        val b = phone(remote)
        b.signIn(userA)
        runCurrent()
        val lastId = runBlocking { b.drives.observeLastProcessedDriveId().first() }
        assertNotNull(lastId)
        // Restored drive: its burn is marked as shown, milestones are recorded without a celebration.
        assertEquals(lastId.toString(), b.meta.get("burn_shown_drive_id"))
        assertNotNull(b.meta.get("milestones_achieved"))
        assertNull(b.milestones.pending.value)
        // A drive recorded afterwards on the restored phone is a new, unshown burn.
        b.nextStart = 9_000_000_000_000L
        b.addDrive(1)
        runCurrent()
        val newest = runBlocking { b.drives.observeLastProcessedDriveId().first() }
        assertTrue(newest != lastId)
        assertTrue(b.meta.get("burn_shown_drive_id") != newest.toString())
    }

    @Test
    fun differentAccountWithoutLocalDrivesRebindsSilently() = runTest {
        val remote = FakeRemote()
        val a = phone(remote)
        a.signIn(userA)
        runCurrent()
        a.signOut()
        a.signIn(userB)
        runCurrent()
        assertNull(a.manager.conflict.value)
        assertEquals(userB.uid, a.syncStore.boundUid)
    }

    private fun TestScope.conflictSetup(remote: FakeRemote): Phone {
        val p = phone(remote)
        p.addDrive()
        p.addDrive()
        p.signIn(userA)
        runCurrent()
        p.signOut()
        runCurrent()
        assertEquals(SyncStatus.Disabled, p.manager.status.value)
        p.signIn(userB)
        runCurrent()
        return p
    }

    @Test
    fun conflictCancelSignsOutAndKeepsLocalData() = runTest {
        val remote = FakeRemote()
        val p = conflictSetup(remote)
        assertEquals(AccountConflict(2, userB.email), p.manager.conflict.value)
        assertEquals(SyncStatus.Disabled, p.manager.status.value)
        p.manager.resolveConflict(ConflictChoice.Cancel)
        runCurrent()
        assertNull(p.manager.conflict.value)
        assertEquals(AuthState.SignedOut, p.backend.flow.value)
        assertEquals(2, p.driveCount())
        assertNull(remote.drives[userB.uid])
    }

    @Test
    fun conflictMergeUploadsLocalDrivesToNewAccount() = runTest {
        val remote = FakeRemote()
        val p = conflictSetup(remote)
        p.manager.resolveConflict(ConflictChoice.MergeLocal)
        runCurrent()
        assertNull(p.manager.conflict.value)
        assertEquals(userB.uid, p.syncStore.boundUid)
        assertEquals(2, remote.drives[userB.uid]?.size)
        assertEquals(2, remote.drives[userA.uid]?.size)
        assertIs<SyncStatus.Idle>(p.manager.status.value)
    }

    @Test
    fun conflictReplaceWipesLocalAndDownloadsAccountData() = runTest {
        val remote = FakeRemote()
        // Account B already has one drive from another phone.
        val other = phone(remote)
        other.addDrive(4)
        other.signIn(userB)
        runCurrent()
        val p = conflictSetup(remote)
        p.manager.resolveConflict(ConflictChoice.ReplaceLocal)
        runCurrent()
        assertNull(p.manager.conflict.value)
        assertTrue(p.backend.flow.value is AuthState.SignedIn)
        assertEquals(userB.uid, p.syncStore.boundUid)
        assertEquals(1, p.driveCount())
        assertEquals(other.drives.drivenSegmentIds(), p.drives.drivenSegmentIds())
        assertEquals(1, remote.drives[userB.uid]?.size)
    }

    @Test
    fun offlineFailureKeepsPendingAndRecovers() = runTest {
        val remote = FakeRemote()
        val p = phone(remote)
        p.signIn(userA)
        runCurrent()
        assertIs<SyncStatus.Idle>(p.manager.status.value)
        remote.failing = true
        p.addDrive()
        runCurrent()
        val failed = p.manager.status.value
        assertIs<SyncStatus.Failed>(failed)
        assertEquals(SyncErrorKind.Network, failed.kind)
        assertEquals(1, failed.pending)
        assertEquals(0, remote.putDriveCalls)
        // Network back: the automatic retry (backoff) uploads it without any user action.
        remote.failing = false
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        val idle = p.manager.status.value
        assertIs<SyncStatus.Idle>(idle)
        assertEquals(0, idle.pending)
        assertEquals(1, remote.putDriveCalls)
    }

    @Test
    fun timeoutsAndNetworkLookingErrorsAreClassifiedAsNetwork() = runTest {
        val timeout = try {
            kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.awaitCancellation() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            e
        }
        val cases = listOf<Throwable>(
            timeout,
            java.net.UnknownHostException("firestore.googleapis.com"),
            java.io.IOException("Unable to resolve host"),
            RuntimeException("wrapper", IllegalStateException("UNAVAILABLE: Unable to resolve host")),
            RuntimeException("DEADLINE_EXCEEDED"),
        )
        for (error in cases) {
            val remote = FakeRemote()
            val p = phone(remote)
            p.addDrive()
            remote.failWith = error
            p.signIn(userA)
            runCurrent()
            val status = p.manager.status.value
            assertIs<SyncStatus.Failed>(status)
            assertEquals(SyncErrorKind.Network, status.kind, error.toString())
            assertEquals(1, status.pending)
        }
    }

    @Test
    fun replaceKeepsOnboardingAndConsent() = runTest {
        val remote = FakeRemote()
        val other = phone(remote)
        other.addDrive(2)
        other.signIn(userB)
        runCurrent()
        val p = conflictSetup(remote)
        p.meta.put("onboarding_completed", "1")
        p.meta.put("consent_version", "1")
        p.manager.resolveConflict(ConflictChoice.ReplaceLocal)
        runCurrent()
        assertEquals("1", p.meta.get("onboarding_completed"))
        assertEquals("1", p.meta.get("consent_version"))
        assertEquals(userB.uid, p.syncStore.boundUid)
        assertEquals(1, p.driveCount())
    }

    @Test
    fun manualSyncRetriesImmediately() = runTest {
        val remote = FakeRemote()
        val p = phone(remote)
        p.signIn(userA)
        runCurrent()
        remote.failing = true
        p.addDrive()
        runCurrent()
        assertIs<SyncStatus.Failed>(p.manager.status.value)
        remote.failing = false
        p.manager.syncNow()
        runCurrent()
        assertEquals(0, (p.manager.status.value as SyncStatus.Idle).pending)
        assertEquals(1, remote.putDriveCalls)
    }

    @Test
    fun profileNewerInCloudIsApplied() = runTest {
        val remote = FakeRemote()
        val a = phone(remote)
        a.signIn(userA)
        runCurrent()
        // Another phone edits its profile later: the cloud copy is newer.
        val newer = a.syncStore.localProfile(1L).copy(
            updatedAtMs = 9_000_000_000L,
            settings = a.syncStore.localProfile(1L).settings + ("distance_unit" to "Miles"),
        )
        remote.profiles[userA.uid] = SyncCodec.encodeProfile(newer)
        val before = remote.putProfileCalls
        a.manager.syncNow()
        runCurrent()
        assertEquals(newer.settings, a.syncStore.localProfile(1L).settings)
        assertEquals(before, remote.putProfileCalls)
    }

    @Test
    fun profileNewerLocallyIsUploadedOnlyWhenChanged() = runTest {
        val remote = FakeRemote()
        val a = phone(remote)
        a.signIn(userA)
        runCurrent()
        val local = a.syncStore.localProfile(1L)
        // Local edit stamped newer than the cloud copy.
        a.syncStore.applyProfile(local.copy(updatedAtMs = 9_000_000_000L, settings = local.settings + ("distance_unit" to "Miles")))
        val before = remote.putProfileCalls
        a.manager.syncNow()
        runCurrent()
        assertEquals(before + 1, remote.putProfileCalls)
        // Nothing changed since: no further profile write.
        a.manager.syncNow()
        runCurrent()
        assertEquals(before + 1, remote.putProfileCalls)
    }

    @Test
    fun deleteCloudDataRemovesRemoteDocuments() = runTest {
        val remote = FakeRemote()
        val p = phone(remote)
        p.addDrive()
        p.signIn(userA)
        runCurrent()
        assertTrue(p.manager.deleteCloudData())
        assertNull(remote.drives[userA.uid])
        assertNull(remote.profiles[userA.uid])
    }

    @Test
    fun remoteIdsAreListedOncePerSyncAndCachedAfterDrives() = runTest {
        val remote = FakeRemote()
        val p = phone(remote)
        p.signIn(userA)
        runCurrent()
        val listed = remote.listCalls
        p.addDrive()
        runCurrent()
        // A processed drive uploads using the cached ids: no new listing.
        assertEquals(listed, remote.listCalls)
        assertEquals(1, remote.putDriveCalls)
    }
}
