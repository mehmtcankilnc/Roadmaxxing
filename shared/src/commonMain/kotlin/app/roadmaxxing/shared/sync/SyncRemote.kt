package app.roadmaxxing.shared.sync

/** Firestore implementation lives in composeApp. Plain maps keep shared Firebase-free. */
interface SyncRemote {
    suspend fun listDriveIds(uid: String): Set<String>
    suspend fun fetchDrives(uid: String, ids: Collection<String>): List<Map<String, Any?>>
    suspend fun putDrive(uid: String, syncId: String, doc: Map<String, Any?>)
    suspend fun fetchProfile(uid: String): Map<String, Any?>?
    suspend fun putProfile(uid: String, doc: Map<String, Any?>)
    suspend fun deleteAll(uid: String)
}

/** Used until the platform registers a real remote: every call fails. */
class NoopSyncRemote : SyncRemote {
    override suspend fun listDriveIds(uid: String): Set<String> = unavailable()
    override suspend fun fetchDrives(uid: String, ids: Collection<String>): List<Map<String, Any?>> = unavailable()
    override suspend fun putDrive(uid: String, syncId: String, doc: Map<String, Any?>): Unit = unavailable()
    override suspend fun fetchProfile(uid: String): Map<String, Any?>? = unavailable()
    override suspend fun putProfile(uid: String, doc: Map<String, Any?>): Unit = unavailable()
    override suspend fun deleteAll(uid: String): Unit = unavailable()

    private fun unavailable(): Nothing = throw IllegalStateException("sync unavailable")
}
