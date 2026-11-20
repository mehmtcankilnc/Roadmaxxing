package app.roadmaxxing.sync

/**
 * Failure of a cloud call made by [FirestoreSyncRemote]. Every [app.roadmaxxing.shared.sync.SyncRemote] method throws
 * only these (plus `CancellationException`, which is never swallowed).
 *
 * - [Network]: offline, DNS/connection trouble, deadline exceeded or the call timed out. Safe to retry later.
 * - [Permission]: Firestore security rules (or an expired/absent sign-in) rejected the call. Retrying will not help
 *   until the user signs in again or the rules are fixed.
 * - [Quota]: the free-tier daily quota or a rate limit was exhausted. Retry after the daily reset.
 * - [Unknown]: anything else (including malformed server data).
 */
sealed class SyncError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(message: String = "network", cause: Throwable? = null) : SyncError(message, cause)
    class Permission(message: String = "permission", cause: Throwable? = null) : SyncError(message, cause)
    class Quota(message: String = "quota", cause: Throwable? = null) : SyncError(message, cause)
    class Unknown(message: String = "unknown", cause: Throwable? = null) : SyncError(message, cause)
}

/**
 * Maps a raw Firebase failure to a [SyncError]. GitLive exposes a different exception type per platform, so this
 * classifies by the status-code text that both SDKs put in the message (for example `PERMISSION_DENIED: ...`) and by
 * the well-known wording. Only the classification is kept as the message; the original text (which can contain
 * document paths) stays in [Throwable.cause].
 */
internal fun Throwable.toSyncError(): SyncError {
    if (this is SyncError) return this
    val text = (message ?: "").lowercase()
    return when {
        "permission_denied" in text || "permission denied" in text || "missing or insufficient permissions" in text ||
            "unauthenticated" in text -> SyncError.Permission(cause = this)
        "resource_exhausted" in text || "quota" in text || "resource has been exhausted" in text ->
            SyncError.Quota(cause = this)
        "unavailable" in text || "deadline_exceeded" in text || "deadline exceeded" in text ||
            "network" in text || "offline" in text || "unable to resolve host" in text ||
            "timed out" in text || "timeout" in text || "could not reach" in text -> SyncError.Network(cause = this)
        else -> SyncError.Unknown(cause = this)
    }
}
