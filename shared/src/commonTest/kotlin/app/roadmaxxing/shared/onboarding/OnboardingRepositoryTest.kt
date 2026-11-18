package app.roadmaxxing.shared.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingRepositoryTest {
    private class MapStore(val map: MutableMap<String, String> = HashMap()) : AppMetaStore {
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    @Test
    fun freshInstallIsNotCompleted() {
        val repo = OnboardingRepository(MapStore())
        assertFalse(repo.isCompleted.value)
        assertNull(repo.consent())
    }

    @Test
    fun markCompletedPersistsAcrossInstances() {
        val store = MapStore()
        val first = OnboardingRepository(store)
        first.markCompleted()
        assertTrue(first.isCompleted.value)
        assertTrue(OnboardingRepository(store).isCompleted.value)
        assertEquals("1", store.map["onboarding_completed"])
    }

    @Test
    fun consentRoundTrip() {
        val store = MapStore()
        OnboardingRepository(store).recordConsent(acceptedAtEpochMillis = 1_700_000_000_000L)
        val consent = OnboardingRepository(store).consent()
        assertEquals(ConsentRecord(OnboardingRepository.CURRENT_CONSENT_VERSION, 1_700_000_000_000L), consent)
        assertEquals("1700000000000", store.map["consent_at"])
    }

    @Test
    fun explicitVersionIsStored() {
        val store = MapStore()
        val repo = OnboardingRepository(store)
        repo.recordConsent(5L, version = 7)
        assertEquals(ConsentRecord(7, 5L), repo.consent())
    }

    @Test
    fun corruptConsentReadsAsNull() {
        val store = MapStore(mutableMapOf("consent_version" to "x", "consent_at" to "1"))
        assertNull(OnboardingRepository(store).consent())
    }

    @Test
    fun consentVersionConstant() {
        assertEquals(1, OnboardingRepository.CURRENT_CONSENT_VERSION)
    }
}
