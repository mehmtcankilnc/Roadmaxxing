package app.roadmaxxing.shared.sync

import app.roadmaxxing.shared.account.AccountRepository
import app.roadmaxxing.shared.account.AccountUser
import app.roadmaxxing.shared.account.AuthState
import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.map.burn.BurnRepository
import app.roadmaxxing.shared.milestones.MilestoneRepository
import app.roadmaxxing.shared.settings.DataEraser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Syncs local drives and the profile with the signed-in account's cloud data (K1-K5).
 *
 * It observes the auth state from creation on, so it works before any UI exists. Triggers (a processed drive,
 * app foreground, "Sync now") are coalesced into one single-flight worker. Raw GPS points never leave the phone.
 *
 * Quota care: remote ids are listed at most once per sync, and only on the first sync of a session, a manual sync,
 * or a foreground older than [RELIST_AFTER_MS]; after a drive only that drive is uploaded.
 */
class SyncManager(
    private val store: SyncStore,
    private val remote: SyncRemote,
    private val account: AccountRepository,
    private val dataEraser: DataEraser,
    private val driveStore: DriveStore,
    private val clock: () -> Long,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val milestones: MilestoneRepository? = null,
    private val burn: BurnRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Disabled)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _conflict = MutableStateFlow<AccountConflict?>(null)
    val conflict: StateFlow<AccountConflict?> = _conflict.asStateFlow()

    /** The account syncing is allowed for; null when signed out or while a conflict waits for a decision. */
    private val activeUid = MutableStateFlow<String?>(null)
    private var conflictUser: AccountUser? = null

    /** Bit set of [REQ_AUTO], [REQ_FOREGROUND], [REQ_MANUAL]; 0 when nothing is requested. */
    private val requests = MutableStateFlow(0)
    private var retryJob: Job? = null
    private var failures = 0
    private var lastSyncedAtMs: Long? = null

    // In-memory cache of the remote drive ids for the signed-in uid (session only).
    private var cachedUid: String? = null
    private var remoteIds: MutableSet<String>? = null
    private var lastListMs = 0L

    init {
        scope.launch { account.state.collect { onAuth(it) } }
        scope.launch { driveStore.observeLastProcessedDriveId().distinctUntilChanged().collect { request(REQ_AUTO) } }
        scope.launch {
            requests.collect { value ->
                if (value == 0) return@collect
                val bits = requests.getAndUpdate { 0 }
                if (bits != 0) runSync(bits)
            }
        }
    }

    fun resolveConflict(choice: ConflictChoice) {
        val user = conflictUser ?: return
        scope.launch {
            when (choice) {
                ConflictChoice.Cancel -> {
                    conflictUser = null
                    _conflict.value = null
                    account.signOut()
                }
                ConflictChoice.MergeLocal -> {
                    mutex.withLock {
                        store.boundUid = user.uid
                        store.clearSyncState()
                    }
                    activate(user)
                }
                ConflictChoice.ReplaceLocal -> {
                    mutex.withLock {
                        // Erases local data but not the signed-in Firebase session.
                        dataEraser.eraseAll(keepOnboarding = true)
                        store.boundUid = user.uid
                    }
                    activate(user)
                }
            }
        }
    }

    fun syncNow() = request(REQ_MANUAL)

    fun onAppForeground() = request(REQ_FOREGROUND)

    /** Deletes the signed-in user's cloud data; local data stays. Returns false when it failed or nobody is signed in. */
    suspend fun deleteCloudData(): Boolean {
        val uid = (account.state.value as? AuthState.SignedIn)?.user?.uid ?: return false
        return mutex.withLock {
            try {
                remote.deleteAll(uid)
                remoteIds = null
                store.clearSyncState()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun request(bit: Int) {
        if (bit == REQ_MANUAL) {
            retryJob?.cancel()
            retryJob = null
        }
        if (activeUid.value == null) return
        requests.update { it or bit }
    }

    private suspend fun onAuth(state: AuthState) {
        when (state) {
            is AuthState.SignedIn -> onSignedIn(state.user)
            else -> {
                retryJob?.cancel()
                retryJob = null
                activeUid.value = null
                conflictUser = null
                _conflict.value = null
                remoteIds = null
                cachedUid = null
                failures = 0
                _status.value = SyncStatus.Disabled
            }
        }
    }

    private suspend fun onSignedIn(user: AccountUser) {
        val bound = store.boundUid
        when {
            bound == null -> {
                store.boundUid = user.uid
                activate(user)
            }
            bound == user.uid -> activate(user)
            else -> {
                val local = driveStore.observeDrives().first().count { it.summary != null }
                if (local == 0) {
                    store.boundUid = user.uid
                    store.clearSyncState()
                    activate(user)
                } else {
                    activeUid.value = null
                    conflictUser = user
                    _status.value = SyncStatus.Disabled
                    _conflict.value = AccountConflict(local, user.email)
                }
            }
        }
    }

    private fun activate(user: AccountUser) {
        conflictUser = null
        _conflict.value = null
        if (cachedUid != user.uid) {
            cachedUid = user.uid
            remoteIds = null
        }
        activeUid.value = user.uid
        // The first sync of a session (re)lists the remote ids.
        request(REQ_MANUAL)
    }

    private suspend fun runSync(bits: Int) {
        val uid = activeUid.value ?: return
        mutex.withLock {
            if (activeUid.value != uid) return
            val manual = bits and REQ_MANUAL != 0
            val foreground = bits and REQ_FOREGROUND != 0
            try {
                if (store.boundUid == null) store.boundUid = uid
                if (store.boundUid != uid) return
                _status.value = SyncStatus.Syncing(store.pendingCount())
                val relist = remoteIds == null || manual || (foreground && clock() - lastListMs > RELIST_AFTER_MS)
                sync(uid, relist)
                failures = 0
                lastSyncedAtMs = clock()
                _status.value = SyncStatus.Idle(lastSyncedAtMs, store.pendingCount())
            } catch (t: Throwable) {
                // A timeout is a CancellationException too, but it is a failed call, not a cancelled worker.
                if (t is CancellationException && t !is kotlinx.coroutines.TimeoutCancellationException) throw t
                remoteIds = null // may be stale after a failure; list again next time
                val pending = runCatching { store.pendingCount() }.getOrDefault(0)
                _status.value = SyncStatus.Failed(classify(t), lastSyncedAtMs, pending)
                if (!manual) scheduleRetry() else failures = 0
            }
        }
    }

    private suspend fun sync(uid: String, relist: Boolean) {
        store.ensureSyncIds()
        val ids = remoteIds.takeUnless { relist } ?: remote.listDriveIds(uid).toMutableSet().also {
            remoteIds = it
            lastListMs = clock()
        }

        // Profile first: a fresh phone must hold the cloud milestones before drives are imported (no re-celebration).
        val cloudProfile = remote.fetchProfile(uid)?.let { SyncCodec.decodeProfile(it) }
        val localBefore = store.localProfile(clock())
        if (cloudProfile != null && cloudProfile.updatedAtMs > localBefore.updatedAtMs) store.applyProfile(cloudProfile)

        // Download what only the cloud has.
        val missing = ids - store.localSyncIds()
        for (chunk in missing.chunked(DOWNLOAD_CHUNK)) {
            val drives = remote.fetchDrives(uid, chunk).mapNotNull { doc ->
                val id = doc["syncId"] as? String ?: return@mapNotNull null
                SyncCodec.decodeDrive(id, doc)
            }
            if (drives.isNotEmpty()) {
                store.importDrives(drives)
                silenceImported()
            }
        }

        // Upload what only this phone has.
        val unsynced = store.unsyncedDrives()
        var pending = unsynced.size
        val alreadyRemote = unsynced.filter { it.syncId in ids }
        if (alreadyRemote.isNotEmpty()) {
            store.markSynced(alreadyRemote.map { it.syncId }, clock())
            pending -= alreadyRemote.size
        }
        for (drive in unsynced) {
            if (drive.syncId in ids) continue
            remote.putDrive(uid, drive.syncId, SyncCodec.encodeDrive(drive))
            ids.add(drive.syncId)
            store.markSynced(listOf(drive.syncId), clock())
            pending--
            _status.value = SyncStatus.Syncing(pending.coerceAtLeast(0))
        }

        pushProfile(uid, cloudProfile)
    }

    /**
     * A restore or merge download must be silent: no burn for imported drives and no celebration for milestones
     * crossed only because of imported data. Locally recorded drives keep both.
     */
    private suspend fun silenceImported() {
        milestones?.recordSilently()
        val burnRepo = burn ?: return
        val lastId = driveStore.observeLastProcessedDriveId().first() ?: return
        if (driveStore.drive(lastId)?.startReason == IMPORTED_REASON) burnRepo.markShown(lastId)
    }

    /** Last write wins by [RemoteProfile.updatedAtMs]; the cloud document is written only when local is newer. */
    private suspend fun pushProfile(uid: String, cloud: RemoteProfile?) {
        // A never-edited local profile reports stamp 0 (see SyncStore), so a fresh phone never overwrites the cloud profile.
        val local = store.localProfile(clock())
        if (cloud == null || cloud.updatedAtMs < local.updatedAtMs) {
            remote.putProfile(uid, SyncCodec.encodeProfile(local))
        }
    }

    private fun scheduleRetry() {
        val wait = minOf(BACKOFF_BASE_MS shl failures.coerceAtMost(10), BACKOFF_MAX_MS)
        failures++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            requests.update { it or REQ_AUTO }
        }
    }

    private fun classify(t: Throwable): SyncErrorKind {
        // Timeouts (also the coroutine kind, which is a CancellationException) and cause chains count as network.
        if (t is kotlinx.coroutines.TimeoutCancellationException) return SyncErrorKind.Network
        val name = generateSequence(t) { it.cause }.take(4).joinToString(" ") { (it::class.simpleName ?: "") + " " + (it.message ?: "") }
        fun has(vararg keys: String) = keys.any { name.contains(it, ignoreCase = true) }
        return when {
            has("permission", "unauthenticated") -> SyncErrorKind.Permission
            has("quota", "resourceexhausted", "resource_exhausted") -> SyncErrorKind.Quota
            has("network", "unavailable", "timeout", "timed out", "deadline", "ioexception", "offline", "unreachable", "unknownhost", "connect") ->
                SyncErrorKind.Network
            else -> SyncErrorKind.Unknown
        }
    }

    private companion object {
        const val IMPORTED_REASON = "SYNC"
        const val REQ_AUTO = 1
        const val REQ_FOREGROUND = 2
        const val REQ_MANUAL = 4
        const val RELIST_AFTER_MS = 10 * 60 * 1000L
        const val DOWNLOAD_CHUNK = 30
        const val BACKOFF_BASE_MS = 30_000L
        const val BACKOFF_MAX_MS = 15 * 60_000L
    }
}
