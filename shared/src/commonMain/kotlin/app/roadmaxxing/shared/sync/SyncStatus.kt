package app.roadmaxxing.shared.sync

enum class SyncErrorKind { Network, Permission, Quota, Unknown }

sealed interface SyncStatus {
    /** Signed out or accounts unavailable. */
    data object Disabled : SyncStatus

    data class Idle(val lastSyncedAtMs: Long?, val pending: Int) : SyncStatus

    data class Syncing(val pending: Int) : SyncStatus

    data class Failed(val kind: SyncErrorKind, val lastSyncedAtMs: Long?, val pending: Int) : SyncStatus
}

/** Raised when a different account signs in on a phone whose local data is bound to another account (K2). */
data class AccountConflict(val localDriveCount: Int, val newAccountEmail: String?)

enum class ConflictChoice { MergeLocal, ReplaceLocal, Cancel }
