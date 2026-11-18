package app.roadmaxxing.shared.map.undiscovered

import app.roadmaxxing.shared.domain.DriveSummary
import app.roadmaxxing.shared.domain.InMemoryDriveStore
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.map.GeoBounds
import app.roadmaxxing.shared.map.layers.MapLayerPreferences
import app.roadmaxxing.shared.onboarding.AppMetaStore
import app.roadmaxxing.shared.testdata.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UndiscoveredRoadsProviderPerfTest {
    private val centreLat = 36.9914
    private val centreLon = 35.3308

    private fun viewport(halfLat: Double, halfLon: Double, dLon: Double = 0.0) =
        GeoBounds(centreLon - halfLon + dLon, centreLat - halfLat, centreLon + halfLon + dLon, centreLat + halfLat)

    private fun setup(): Triple<UndiscoveredRoadsProvider, InMemoryDriveStore, RegionPackProvider> {
        val packProvider = RegionPackProvider(TestData.packSource("test-data/regions/adana"))
        val store = InMemoryDriveStore()
        return Triple(UndiscoveredRoadsProvider(packProvider, store, Dispatchers.Default), store, packProvider)
    }

    private fun ms(block: () -> Unit): Long {
        val t = System.nanoTime()
        block()
        return (System.nanoTime() - t) / 1_000_000
    }

    private fun featureCount(json: String) = json.split("\"type\":\"Feature\"").size - 1

    @Test
    fun cityCentreColdAndWarm() {
        for ((zoom, half) in listOf(13.0 to 0.02, 14.0 to 0.01)) {
            val (p, _, packProvider) = setup()
            packProvider.pack // load manifest outside the timing
            var json = ""
            val cold = ms { json = runBlocking { p.roadsIn(viewport(half, half * 1.3), zoom) } }
            val warm = ms { runBlocking { p.roadsIn(viewport(half, half * 1.3), zoom) } }
            val pan = ms { json = runBlocking { p.roadsIn(viewport(half, half * 1.3, dLon = half * 1.3), zoom) } }
            println(
                "PERF undiscovered z$zoom: cold=${cold}ms warm=${warm}ms halfPan=${pan}ms " +
                    "size=${json.length / 1024}KiB features=${featureCount(json)} tiles=${p.lastTileCount}",
            )
            assertTrue(cold < 150, "cold z$zoom $cold ms")
            assertTrue(warm <= cold)
            assertTrue(featureCount(json) > 0)
            val maxKiB = if (zoom < 14.0) 1.2 * 1024 else 1.5 * 1024
            assertTrue(json.length / 1024.0 <= maxKiB, "size z$zoom ${json.length / 1024} KiB")
            if (zoom < 14.0) assertTrue(featureCount(json) <= 10_500, "features ${featureCount(json)}")
            assertTrue(packProvider.pack.tileLoads < 60, "tile loads suggest a whole-pack scan")
        }
    }

    @Test
    fun drivenSegmentsAreExcludedAndCountRefreshes() = runBlocking {
        val (p, store, packProvider) = setup()
        val b = viewport(0.01, 0.013)
        val before = p.roadsIn(b, 14.0)
        val inBox = packProvider.pack.segmentsInBox(b.south, b.west, b.north, b.east).take(20)
        val ids = inBox.map { it.id }.toSet()
        val driveId = store.createDrive(1000L, "test")
        store.completeProcessing(driveId, ids, 1000L) {
            DriveSummary(DriveVerdict.Drive, 1.0, 1, 1, 1.0, ids.size, 0, 1, 1)
        }
        val after = p.roadsIn(b, 14.0)
        assertTrue(featureCount(after) > 0)
        assertTrue(after.length < before.length)
    }

    @Test
    fun zoomThresholds() = runBlocking {
        val (p, _, _) = setup()
        val b = viewport(0.01, 0.013)
        assertEquals(0, featureCount(p.roadsIn(b, 10.9)))
        val major = p.roadsIn(b, 12.0)
        assertTrue(featureCount(major) > 0)
        assertFalse(major.contains("\"layer\":\"local\""))
        val all = p.roadsIn(b, 13.0)
        assertTrue(all.contains("\"layer\":\"local\""))
        assertTrue(featureCount(all) > featureCount(major))
    }

    @Test
    fun tileCapRespected() = runBlocking {
        val (p, _, _) = setup()
        val wide = GeoBounds(34.8, 36.6, 35.9, 37.4) // far more than 48 z13 tiles
        val json = p.roadsIn(wide, 13.0)
        assertTrue(p.lastTileCount <= UndiscoveredRoadsProvider.MAX_TILES, "tiles ${p.lastTileCount}")
        assertFalse(json.contains("\"layer\":\"local\""))
        assertTrue(p.cachedTileCount <= UndiscoveredRoadsProvider.MAX_CACHED_TILES)
        println("PERF undiscovered capped: tiles=${p.lastTileCount} size=${json.length / 1024}KiB")
    }

    @Test
    fun layerPreferencesDefaultTrueAndRoundTrip() {
        val map = HashMap<String, String>()
        val meta = object : AppMetaStore {
            override fun get(key: String) = map[key]
            override fun put(key: String, value: String) { map[key] = value }
        }
        val prefs = MapLayerPreferences(meta)
        assertTrue(prefs.showUndiscovered.value)
        assertTrue(prefs.showPrivacyZones.value)
        prefs.setShowUndiscovered(false)
        prefs.setShowPrivacyZones(false)
        assertFalse(prefs.showUndiscovered.value)
        assertEquals("false", map["layer_undiscovered"])
        val again = MapLayerPreferences(meta)
        assertFalse(again.showUndiscovered.value)
        assertFalse(again.showPrivacyZones.value)
        again.setShowUndiscovered(true)
        assertTrue(MapLayerPreferences(meta).showUndiscovered.value)
    }
}
