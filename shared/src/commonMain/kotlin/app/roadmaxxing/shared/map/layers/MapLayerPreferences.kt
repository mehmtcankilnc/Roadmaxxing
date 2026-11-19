package app.roadmaxxing.shared.map.layers

import app.roadmaxxing.shared.onboarding.AppMetaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Map layer toggles, persisted in `app_meta` (keys `layer_undiscovered`, `layer_privacy_zones`).
 * Both layers default to on (a missing key means on); only the literal "false" turns a layer off.
 */
class MapLayerPreferences(private val store: AppMetaStore) {
    private val undiscovered = MutableStateFlow(read(KEY_UNDISCOVERED))
    private val privacyZones = MutableStateFlow(read(KEY_PRIVACY_ZONES))

    val showUndiscovered: StateFlow<Boolean> = undiscovered.asStateFlow()
    val showPrivacyZones: StateFlow<Boolean> = privacyZones.asStateFlow()

    fun setShowUndiscovered(value: Boolean) {
        store.put(KEY_UNDISCOVERED, value.toString())
        undiscovered.value = value
    }

    fun setShowPrivacyZones(value: Boolean) {
        store.put(KEY_PRIVACY_ZONES, value.toString())
        privacyZones.value = value
    }

    /** Re-reads storage (used after all data was erased; missing keys mean on). */
    fun reload() {
        undiscovered.value = read(KEY_UNDISCOVERED)
        privacyZones.value = read(KEY_PRIVACY_ZONES)
    }

    private fun read(key: String): Boolean = store.get(key) != "false"

    internal companion object {
        const val KEY_UNDISCOVERED = "layer_undiscovered"
        const val KEY_PRIVACY_ZONES = "layer_privacy_zones"
    }
}
