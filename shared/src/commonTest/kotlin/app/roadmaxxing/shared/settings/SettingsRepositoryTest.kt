package app.roadmaxxing.shared.settings

import app.roadmaxxing.shared.onboarding.AppMetaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsRepositoryTest {
    private class MapStore(val map: MutableMap<String, String> = HashMap()) : AppMetaStore {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    @Test
    fun defaults() {
        val s = SettingsRepository(MapStore())
        assertEquals(DistanceUnit.Kilometers, s.distanceUnit.value)
        assertEquals(QuietHours.Default, s.quietHours.value)
    }

    @Test
    fun regionDefaultOnlyWhenUserNeverChose() {
        val store = MapStore()
        val s = SettingsRepository(store)
        s.initDefaultUnit("us")
        assertEquals(DistanceUnit.Miles, s.distanceUnit.value)
        s.initDefaultUnit("GB")
        assertEquals(DistanceUnit.Miles, s.distanceUnit.value)
        s.initDefaultUnit("TR")
        assertEquals(DistanceUnit.Kilometers, s.distanceUnit.value)
        s.initDefaultUnit(null)
        assertEquals(DistanceUnit.Kilometers, s.distanceUnit.value)
        assertNull(store.get("distance_unit")) // never persisted as a user choice

        s.setDistanceUnit(DistanceUnit.Kilometers)
        s.initDefaultUnit("US")
        assertEquals(DistanceUnit.Kilometers, s.distanceUnit.value)
    }

    @Test
    fun valuesPersistAcrossInstances() {
        val store = MapStore()
        val s = SettingsRepository(store)
        s.setDistanceUnit(DistanceUnit.Miles)
        s.setQuietHours(QuietHours(true, 23 * 60, 6 * 60 + 30))
        val again = SettingsRepository(store)
        assertEquals(DistanceUnit.Miles, again.distanceUnit.value)
        assertEquals(QuietHours(true, 1380, 390), again.quietHours.value)
    }

    @Test
    fun corruptQuietValueFallsBackToDefault() {
        val s = SettingsRepository(MapStore(mutableMapOf("quiet_hours" to "x|y")))
        assertEquals(QuietHours.Default, s.quietHours.value)
    }
}
