package app.roadmaxxing.shared.domain

/** State of the local data layer as seen by the UI. */
enum class SetupStatus {
    /** Local database is open and its schema matches the app. */
    Ready,

    /** First launch: no schema version stored yet. */
    FirstLaunch,

    /** Stored schema version differs from the current one. */
    SchemaMismatch,
}

/** Pure decision logic, kept separate from storage so it can be unit tested on every platform. */
object SetupStatusResolver {
    fun resolve(storedSchemaVersion: Long?, currentSchemaVersion: Long): SetupStatus = when (storedSchemaVersion) {
        null -> SetupStatus.FirstLaunch
        currentSchemaVersion -> SetupStatus.Ready
        else -> SetupStatus.SchemaMismatch
    }
}
