package app.roadmaxxing.shared.settings

import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.map.DrivenRoadsProvider
import app.roadmaxxing.shared.map.layers.MapLayerPreferences
import app.roadmaxxing.shared.milestones.MilestoneRepository
import app.roadmaxxing.shared.onboarding.OnboardingRepository
import app.roadmaxxing.shared.privacy.PrivacyZoneRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Deletes everything the user created. Irreversible. */
class DataEraser(
    private val database: RoadmaxxingDatabase,
    private val onboarding: OnboardingRepository,
    private val privacyZones: PrivacyZoneRepository,
    private val layerPreferences: MapLayerPreferences,
    private val settings: SettingsRepository,
    private val drivenRoads: DrivenRoadsProvider,
    private val dispatcher: CoroutineDispatcher,
    private val milestones: MilestoneRepository? = null,
) {
    /**
     * Deletes all user data in one transaction and resets in-memory state; afterwards onboarding is not completed,
     * unless [keepOnboarding] is set: then the onboarding and consent keys survive (used when a signed-in user
     * replaces this phone's data with the account's cloud data).
     */
    suspend fun eraseAll(keepOnboarding: Boolean = false) {
        withContext(dispatcher) {
            database.transaction {
                // The keys are read first: the bulk delete below removes everything except the schema version.
                val kept = if (keepOnboarding) {
                    KEPT_WITH_ONBOARDING.mapNotNull { key ->
                        database.appMetaQueries.selectValue(key).executeAsOneOrNull()?.let { key to it }
                    }
                } else {
                    emptyList()
                }
                database.tracePointQueries.deleteAll()
                database.driveQueries.deleteAll()
                database.drivenSegmentQueries.deleteAll()
                database.privacyZoneQueries.deleteAll()
                // Also removes the burn-shown flag, privacy suggestion flag, consent and layer keys.
                database.appMetaQueries.deleteAllExcept(SCHEMA_VERSION_KEY)
                for ((key, value) in kept) database.appMetaQueries.upsertValue(key, value)
            }
            onboarding.reload()
            privacyZones.reload()
            layerPreferences.reload()
            settings.reload()
            drivenRoads.reset()
            milestones?.reload()
        }
    }

    private companion object {
        const val SCHEMA_VERSION_KEY = "schema_version"
        val KEPT_WITH_ONBOARDING = listOf("onboarding_completed", "consent_version", "consent_at")
    }
}
