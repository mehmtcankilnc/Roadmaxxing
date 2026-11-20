package app.roadmaxxing.sync

import app.roadmaxxing.shared.sync.SyncRemote
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.DocumentReference
import dev.gitlive.firebase.firestore.DocumentSnapshot
import dev.gitlive.firebase.firestore.FieldPath
import dev.gitlive.firebase.firestore.Source
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Firestore implementation of [SyncRemote] (GitLive `firebase-firestore` 2.8.0).
 *
 * Layout: profile at `users/{uid}`, drives at `users/{uid}/drives/{syncId}`. Documents are plain
 * `Map<String, Any?>` trees of String/Long/Double/Boolean/List/Map. GitLive 2.x picks its own `FirebaseMapSerializer`
 * for a `Map` value, so `set(map)` writes the tree as-is; no `@Serializable` wrapper is needed. Reading cannot use
 * `data<Map<String, Any?>>()` (no serializer for `Any`; `encodedData()` is internal), so reads decode through the
 * field tables in `DocSerializers.kt`. Numbers come back as Long or Double.
 *
 * [fetchDrives] adds the document id to each returned map under `syncId` (SyncManager reads it from there).
 *
 * Every call is bounded by [TIMEOUT_MS] and fails only with [SyncError]. Reads use [Source.SERVER] so a stale local
 * cache can never make a missing document look present. Writes are idempotent `set`s.
 *
 * Read cost (Spark: 50k reads/day): Firestore cannot list document ids without reading the documents, and
 * GitLive 2.8.0 has no field projection. So [listDriveIds] costs ONE READ PER DRIVE DOCUMENT (pages of
 * [PAGE_SIZE]); callers must list rarely. [fetchDrives] costs one read per id requested. [fetchProfile] costs 1,
 * [putDrive]/[putProfile] cost 1 write. [deleteAll] costs one read plus one delete per drive, plus 1 delete.
 */
class FirestoreSyncRemote : SyncRemote {

    private fun profileRef(uid: String): DocumentReference = Firebase.firestore.collection(USERS).document(checkUid(uid))

    private fun drivesRef(uid: String) = profileRef(uid).collection(DRIVES)

    override suspend fun listDriveIds(uid: String): Set<String> = call {
        val ids = HashSet<String>()
        var last: DocumentSnapshot? = null
        while (true) {
            var query = drivesRef(uid).limit(PAGE_SIZE)
            last?.let { query = query.startAfter(it) }
            val docs = query.get(Source.SERVER).documents
            docs.forEach { ids += it.id }
            if (docs.size < PAGE_SIZE) break
            last = docs.last()
        }
        ids
    }

    override suspend fun fetchDrives(uid: String, ids: Collection<String>): List<Map<String, Any?>> = call {
        val out = ArrayList<Map<String, Any?>>(ids.size)
        for (chunk in ids.distinct().chunked(WHERE_IN_LIMIT)) {
            val docs = drivesRef(uid).where { FieldPath.documentId inArray chunk }.get(Source.SERVER).documents
            for (doc in docs) out += doc.toDriveMap() + (SYNC_ID to doc.id)
        }
        out
    }

    override suspend fun putDrive(uid: String, syncId: String, doc: Map<String, Any?>): Unit = call {
        drivesRef(uid).document(checkId(syncId)).set(doc)
    }

    override suspend fun fetchProfile(uid: String): Map<String, Any?>? = call {
        val snap = profileRef(uid).get(Source.SERVER)
        if (snap.exists) snap.toProfileMap() else null
    }

    override suspend fun putProfile(uid: String, doc: Map<String, Any?>): Unit = call {
        profileRef(uid).set(doc)
    }

    override suspend fun deleteAll(uid: String): Unit = call(timeoutMs = DELETE_TIMEOUT_MS) {
        val firestore = Firebase.firestore
        while (true) {
            val docs = drivesRef(uid).limit(BATCH_SIZE).get(Source.SERVER).documents
            if (docs.isEmpty()) break
            val batch = firestore.batch()
            docs.forEach { batch.delete(it.reference) }
            batch.commit()
        }
        profileRef(uid).delete()
    }

    private fun DocumentSnapshot.toDriveMap(): Map<String, Any?> = data(DriveDocSerializer)

    private fun DocumentSnapshot.toProfileMap(): Map<String, Any?> = data(ProfileDocSerializer)

    private fun checkUid(uid: String): String {
        if (uid.isEmpty() || '/' in uid) throw SyncError.Unknown("bad uid")
        return uid
    }

    private fun checkId(id: String): String {
        if (id.isEmpty() || '/' in id) throw SyncError.Unknown("bad sync id")
        return id
    }

    private class Box<T>(val value: T)

    private suspend fun <T> call(timeoutMs: Long = TIMEOUT_MS, block: suspend () -> T): T {
        val boxed = try {
            withTimeoutOrNull(timeoutMs) { Box(block()) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw t.toSyncError()
        } ?: throw SyncError.Network("timeout")
        return boxed.value
    }

    private companion object {
        const val USERS = "users"
        const val DRIVES = "drives"
        const val SYNC_ID = "syncId"
        const val TIMEOUT_MS = 30_000L
        const val DELETE_TIMEOUT_MS = 120_000L
        const val PAGE_SIZE = 300
        const val WHERE_IN_LIMIT = 30
        const val BATCH_SIZE = 500
    }
}
