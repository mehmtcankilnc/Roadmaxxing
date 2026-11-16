package app.roadmaxxing.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SetupStatusResolverTest {

    @Test
    fun noStoredVersionMeansFirstLaunch() {
        assertEquals(SetupStatus.FirstLaunch, SetupStatusResolver.resolve(storedSchemaVersion = null, currentSchemaVersion = 1))
    }

    @Test
    fun matchingVersionMeansReady() {
        assertEquals(SetupStatus.Ready, SetupStatusResolver.resolve(storedSchemaVersion = 3, currentSchemaVersion = 3))
    }

    @Test
    fun differentVersionMeansSchemaMismatch() {
        assertEquals(SetupStatus.SchemaMismatch, SetupStatusResolver.resolve(storedSchemaVersion = 1, currentSchemaVersion = 2))
    }
}
