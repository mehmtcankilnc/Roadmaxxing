package app.roadmaxxing.shared.onboarding

import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The accepted legal-consent version and when it was accepted. */
data class ConsentRecord(val version: Int, val acceptedAtEpochMillis: Long)

/** Minimal key-value storage behind [OnboardingRepository] (the `app_meta` table in production). */
interface AppMetaStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** [AppMetaStore] backed by the SQLDelight `app_meta` table. Calls are small and synchronous. */
class SqlAppMetaStore(private val database: RoadmaxxingDatabase) : AppMetaStore {
    override fun get(key: String): String? =
        database.appMetaQueries.selectValue(key).executeAsOneOrNull()

    override fun put(key: String, value: String) {
        database.appMetaQueries.upsertValue(key, value)
    }
}

/**
 * Onboarding completion and legal-consent state, persisted in `app_meta`
 * (keys `onboarding_completed`, `consent_version`, `consent_at`); no schema change.
 *
 * [isCompleted] is read synchronously from storage when the repository is created, so the first
 * frame already knows whether to show onboarding.
 */
class OnboardingRepository(private val store: AppMetaStore) {
    constructor(database: RoadmaxxingDatabase) : this(SqlAppMetaStore(database))

    private val completed = MutableStateFlow(store.get(KEY_COMPLETED) == TRUE)

    val isCompleted: StateFlow<Boolean> = completed.asStateFlow()

    /** Re-reads storage (used after all data was erased). */
    fun reload() {
        completed.value = store.get(KEY_COMPLETED) == TRUE
    }

    fun markCompleted() {
        store.put(KEY_COMPLETED, TRUE)
        completed.value = true
    }

    /** The stored consent, or null when none was recorded (or the stored values are unreadable). */
    fun consent(): ConsentRecord? {
        val version = store.get(KEY_CONSENT_VERSION)?.toIntOrNull() ?: return null
        val at = store.get(KEY_CONSENT_AT)?.toLongOrNull() ?: return null
        return ConsentRecord(version, at)
    }

    fun recordConsent(acceptedAtEpochMillis: Long, version: Int = CURRENT_CONSENT_VERSION) {
        store.put(KEY_CONSENT_VERSION, version.toString())
        store.put(KEY_CONSENT_AT, acceptedAtEpochMillis.toString())
    }

    companion object {
        const val CURRENT_CONSENT_VERSION: Int = 1
        private const val KEY_COMPLETED = "onboarding_completed"
        private const val KEY_CONSENT_VERSION = "consent_version"
        private const val KEY_CONSENT_AT = "consent_at"
        private const val TRUE = "1"
    }
}
