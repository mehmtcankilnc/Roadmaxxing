package app.roadmaxxing.shared.settings

import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.map.DrivenRoadsProvider
import app.roadmaxxing.shared.map.layers.MapLayerPreferences
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
) {
    /** Deletes all user data in one transaction and resets in-memory state; afterwards onboarding is not completed. */
    suspend fun eraseAll() {
        withContext(dispatcher) {
            database.transaction {
                database.tracePointQueries.deleteAll()
                database.driveQueries.deleteAll()
                database.drivenSegmentQueries.deleteAll()
                database.privacyZoneQueries.deleteAll()
                // Also removes the burn-shown flag, privacy suggestion flag, consent and layer keys.
                database.appMetaQueries.deleteAllExcept(SCHEMA_VERSION_KEY)
            }
            onboarding.reload()
            privacyZones.reload()
            layerPreferences.reload()
            settings.reload()
            drivenRoads.reset()
        }
    }

    private companion object {
        const val SCHEMA_VERSION_KEY = "schema_version"
    }
}
