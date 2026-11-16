package app.roadmaxxing.shared.data

import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.domain.SetupStatus
import app.roadmaxxing.shared.domain.SetupStatusResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Reads and records the local schema version. */
class SetupRepository(
    private val database: RoadmaxxingDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun checkAndRecord(): SetupStatus = withContext(ioDispatcher) {
        val current = RoadmaxxingDatabase.Schema.version
        val queries = database.appMetaQueries
        val stored = queries.selectValue(KEY_SCHEMA_VERSION).executeAsOneOrNull()?.toLongOrNull()
        val status = SetupStatusResolver.resolve(stored, current)
        if (status == SetupStatus.FirstLaunch) {
            queries.upsertValue(KEY_SCHEMA_VERSION, current.toString())
        }
        status
    }

    private companion object {
        const val KEY_SCHEMA_VERSION = "schema_version"
    }
}
