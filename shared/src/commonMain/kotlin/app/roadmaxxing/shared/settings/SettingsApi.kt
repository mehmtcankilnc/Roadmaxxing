package app.roadmaxxing.shared.settings

import app.roadmaxxing.shared.onboarding.AppMetaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DistanceUnit { Kilometers, Miles }

data class QuietHours(val enabled: Boolean, val startMinute: Int, val endMinute: Int) {
    /**
     * True when [minuteOfDay] (0..1439) is inside the range; handles ranges that cross midnight.
     * Start is inclusive, end is exclusive. Equal start and end is an empty range.
     */
    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay >= startMinute && minuteOfDay < endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    companion object {
        val Default = QuietHours(enabled = false, startMinute = 22 * 60, endMinute = 7 * 60)
    }
}

/**
 * User settings persisted in `app_meta` (keys `distance_unit`, `quiet_hours`); no schema change.
 * A region-based default unit is kept in memory only, so it never counts as a user choice.
 */
class SettingsRepository(private val store: AppMetaStore) {
    private val unit = MutableStateFlow(readUnit() ?: DistanceUnit.Kilometers)
    private val quiet = MutableStateFlow(readQuiet())

    val distanceUnit: StateFlow<DistanceUnit> = unit.asStateFlow()
    val quietHours: StateFlow<QuietHours> = quiet.asStateFlow()

    fun setDistanceUnit(unit: DistanceUnit) {
        store.put(KEY_UNIT, if (unit == DistanceUnit.Miles) "mi" else "km")
        this.unit.value = unit
    }

    fun setQuietHours(value: QuietHours) {
        val clean = value.copy(
            startMinute = value.startMinute.coerceIn(0, 1439),
            endMinute = value.endMinute.coerceIn(0, 1439),
        )
        store.put(KEY_QUIET, "${if (clean.enabled) 1 else 0}|${clean.startMinute}|${clean.endMinute}")
        quiet.value = clean
    }

    /** Sets Miles for region "US"/"GB" (case-insensitive), else Kilometers, only if the user never chose a unit. */
    fun initDefaultUnit(regionCode: String?) {
        if (readUnit() != null) return
        val region = regionCode?.trim()?.uppercase()
        unit.value = if (region == "US" || region == "GB") DistanceUnit.Miles else DistanceUnit.Kilometers
    }

    /** Re-reads storage (after an erase the stored keys are gone, so defaults return). */
    fun reload() {
        unit.value = readUnit() ?: DistanceUnit.Kilometers
        quiet.value = readQuiet()
    }

    private fun readUnit(): DistanceUnit? = when (store.get(KEY_UNIT)) {
        "mi" -> DistanceUnit.Miles
        "km" -> DistanceUnit.Kilometers
        else -> null
    }

    private fun readQuiet(): QuietHours {
        val parts = store.get(KEY_QUIET)?.split('|') ?: return QuietHours.Default
        if (parts.size != 3) return QuietHours.Default
        val start = parts[1].toIntOrNull()?.takeIf { it in 0..1439 } ?: return QuietHours.Default
        val end = parts[2].toIntOrNull()?.takeIf { it in 0..1439 } ?: return QuietHours.Default
        return QuietHours(enabled = parts[0] == "1", startMinute = start, endMinute = end)
    }

    private companion object {
        const val KEY_UNIT = "distance_unit"
        const val KEY_QUIET = "quiet_hours"
    }
}
