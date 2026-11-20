package app.roadmaxxing.shared.account

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
import app.roadmaxxing.shared.region.FixturePack
import app.roadmaxxing.shared.settings.DataEraser
import app.roadmaxxing.shared.settings.SettingsRepository
import app.roadmaxxing.shared.sync.NoopSyncRemote
import app.roadmaxxing.shared.sync.SyncRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountRepositoryTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RoadmaxxingDatabase.Schema.create(it) }
    private val db = RoadmaxxingDatabase(driver)
    private val store = SqlDriveStore(db, Dispatchers.Unconfined)
    private val meta = SqlAppMetaStore(db)
    private val user = AccountUser("uid1", "a@b.c", null, AuthProvider.Email)

    private class FakeBackend(initial: AuthState = AuthState.SignedOut) : AuthBackend {
        val flow = MutableStateFlow(initial)
        var deleteResult: AuthResult = AuthResult.Success
        var deleteCalls = 0
        override val state get() = flow
        override suspend fun signUpWithEmail(email: String, password: String): AuthResult = AuthResult.Success
        override suspend fun signInWithEmail(email: String, password: String): AuthResult = AuthResult.Success
        override suspend fun sendPasswordReset(email: String): AuthResult = AuthResult.Success
        override suspend fun signInWithGoogle(): AuthResult = AuthResult.Success
        override suspend fun signOut() { flow.value = AuthState.SignedOut }
        override suspend fun deleteAccount(): AuthResult { deleteCalls++; return deleteResult }
    }

    private fun addDrive(start: Long, verdict: DriveVerdict) {
        val id = store.createDrive(start, "test")
        store.appendPoints(id, List(5) { i -> TracePoint(start + i * 1000L, 37.0, 35.3, 10.0, null, 5.0) })
        store.markEnded(id, start + 5000L, "stopped", start, start + 5000L)
        store.completeProcessing(id, emptyList(), start) {
            DriveSummary(verdict, 5000.0, 600_000, 5, 1.0, 0, 0, 5, start)
        }
    }

    private fun eraser(): DataEraser {
        val packProvider = RegionPackProvider(FixturePack.source())
        return DataEraser(
            db, OnboardingRepository(meta), PrivacyZoneRepository(db, store, meta, Dispatchers.Unconfined),
            MapLayerPreferences(meta), SettingsRepository(meta),
            DrivenRoadsProvider(packProvider, store, Dispatchers.Unconfined), Dispatchers.Unconfined,
        )
    }

    private class FakeRemote(var failure: Throwable? = null) : SyncRemote {
        val deletedUids = mutableListOf<String>()
        override suspend fun listDriveIds(uid: String): Set<String> = emptySet()
        override suspend fun fetchDrives(uid: String, ids: Collection<String>): List<Map<String, Any?>> = emptyList()
        override suspend fun putDrive(uid: String, syncId: String, doc: Map<String, Any?>) {}
        override suspend fun fetchProfile(uid: String): Map<String, Any?>? = null
        override suspend fun putProfile(uid: String, doc: Map<String, Any?>) {}
        override suspend fun deleteAll(uid: String) {
            failure?.let { throw it }
            deletedUids += uid
        }
    }

    private fun repo(backend: AuthBackend, remote: SyncRemote = FakeRemote()) =
        AccountRepository(backend, store, meta, eraser(), Dispatchers.Unconfined, remote)

    private fun driveCount(): Long = driver.executeQuery(null, "SELECT count(*) FROM drive", { c ->
        c.next()
        QueryResult.Value(c.getLong(0)!!)
    }, 0).value

    @Test
    fun thirdCountedDriveTriggersPromptNotNonDrives() {
        val r = repo(FakeBackend())
        addDrive(1_000_000, DriveVerdict.Drive)
        addDrive(2_000_000, DriveVerdict.Drive)
        addDrive(3_000_000, DriveVerdict.TooShort)
        assertNull(r.prompt.value)
        addDrive(4_000_000, DriveVerdict.Drive)
        assertEquals(AccountPromptReason.ThirdDrive, r.prompt.value)
    }

    @Test
    fun firstShareTriggersPromptOnce() {
        val r = repo(FakeBackend())
        assertNull(r.prompt.value)
        r.onShareAttempted()
        assertEquals(AccountPromptReason.FirstShare, r.prompt.value)
        r.dismissPrompt()
        assertNull(r.prompt.value)
        r.onShareAttempted()
        assertNull(r.prompt.value)
    }

    @Test
    fun dismissPersistsAcrossNewInstance() {
        val backend = FakeBackend()
        repeat(3) { addDrive(1_000_000L * (it + 1), DriveVerdict.Drive) }
        val first = repo(backend)
        assertEquals(AccountPromptReason.ThirdDrive, first.prompt.value)
        first.dismissPrompt()
        assertNull(first.prompt.value)
        assertNull(repo(backend).prompt.value)

        first.onShareAttempted()
        first.dismissPrompt()
        val third = repo(backend)
        third.onShareAttempted()
        assertNull(third.prompt.value)
    }

    @Test
    fun onePromptAtATime() {
        repeat(3) { addDrive(1_000_000L * (it + 1), DriveVerdict.Drive) }
        val r = repo(FakeBackend())
        r.onShareAttempted()
        assertEquals(AccountPromptReason.FirstShare, r.prompt.value)
        r.dismissPrompt()
        assertEquals(AccountPromptReason.ThirdDrive, r.prompt.value)
        r.dismissPrompt()
        assertNull(r.prompt.value)
    }

    @Test
    fun noPromptWhenSignedInOrUnavailable() {
        repeat(3) { addDrive(1_000_000L * (it + 1), DriveVerdict.Drive) }
        val signedIn = repo(FakeBackend(AuthState.SignedIn(user)))
        signedIn.onShareAttempted()
        assertNull(signedIn.prompt.value)
        val unavailable = repo(NoopAuthBackend())
        unavailable.onShareAttempted()
        assertNull(unavailable.prompt.value)
    }

    @Test
    fun promptDisappearsOnSignIn() {
        val backend = FakeBackend()
        val r = repo(backend)
        r.onShareAttempted()
        assertEquals(AccountPromptReason.FirstShare, r.prompt.value)
        backend.flow.value = AuthState.SignedIn(user)
        assertNull(r.prompt.value)
    }

    @Test
    fun deleteWithoutLocalKeepsDrives() = runBlocking {
        addDrive(1_000_000, DriveVerdict.Drive)
        val backend = FakeBackend(AuthState.SignedIn(user))
        assertEquals(AuthResult.Success, repo(backend).deleteAccount(alsoDeleteLocal = false))
        assertEquals(1, backend.deleteCalls)
        assertEquals(1L, driveCount())
    }

    @Test
    fun deleteWithLocalErasesDrives() = runBlocking {
        addDrive(1_000_000, DriveVerdict.Drive)
        val backend = FakeBackend(AuthState.SignedIn(user))
        assertEquals(AuthResult.Success, repo(backend).deleteAccount(alsoDeleteLocal = true))
        assertEquals(0L, driveCount())
    }

    @Test
    fun requiresRecentLoginReturnedUnchangedAndKeepsLocal() = runBlocking {
        addDrive(1_000_000, DriveVerdict.Drive)
        val backend = FakeBackend(AuthState.SignedIn(user)).apply {
            deleteResult = AuthResult.Failure(AuthError.RequiresRecentLogin)
        }
        val result = repo(backend).deleteAccount(alsoDeleteLocal = true)
        assertEquals(AuthResult.Failure(AuthError.RequiresRecentLogin), result)
        assertEquals(1L, driveCount())
    }

    @Test
    fun cloudDataIsDeletedBeforeTheAccount() = runBlocking {
        val backend = FakeBackend(AuthState.SignedIn(user))
        val remote = FakeRemote()
        assertEquals(AuthResult.Success, repo(backend, remote).deleteAccount(alsoDeleteLocal = false))
        assertEquals(listOf("uid1"), remote.deletedUids)
        assertEquals(1, backend.deleteCalls)
    }

    @Test
    fun cloudDeleteFailureKeepsAccountAndLocalData() = runBlocking {
        addDrive(1_000_000, DriveVerdict.Drive)
        val backend = FakeBackend(AuthState.SignedIn(user))
        val remote = FakeRemote(failure = RuntimeException("PERMISSION_DENIED"))
        val result = repo(backend, remote).deleteAccount(alsoDeleteLocal = true)
        assertTrue(result is AuthResult.Failure)
        assertEquals(0, backend.deleteCalls)
        assertEquals(1L, driveCount())
    }

    @Test
    fun unavailableSyncRemoteBlocksSignedInDeletion() = runBlocking {
        val backend = FakeBackend(AuthState.SignedIn(user))
        val result = repo(backend, NoopSyncRemote()).deleteAccount(alsoDeleteLocal = false)
        assertEquals(AuthResult.Failure(AuthError.Unknown), result)
        assertEquals(0, backend.deleteCalls)
    }

    @Test
    fun signedOutDeleteSkipsCloud() = runBlocking {
        val backend = FakeBackend(AuthState.SignedOut)
        val remote = FakeRemote()
        repo(backend, remote).deleteAccount(alsoDeleteLocal = false)
        assertTrue(remote.deletedUids.isEmpty())
    }

    @Test
    fun signOutKeepsLocalDrives() = runBlocking {
        addDrive(1_000_000, DriveVerdict.Drive)
        val backend = FakeBackend(AuthState.SignedIn(user))
        val r = repo(backend)
        r.signOut()
        assertEquals(AuthState.SignedOut, r.state.value)
        assertEquals(1L, driveCount())
    }

    @Test
    fun noopBackendFailsEverything() = runBlocking {
        val b = NoopAuthBackend()
        assertEquals(AuthState.Unavailable, b.state.value)
        assertEquals(AuthResult.Failure(AuthError.Unknown), b.signInWithEmail("a", "b"))
        assertEquals(AuthResult.Failure(AuthError.Unknown), b.deleteAccount())
    }
}
