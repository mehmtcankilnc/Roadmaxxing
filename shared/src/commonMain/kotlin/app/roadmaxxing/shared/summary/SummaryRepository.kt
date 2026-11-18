package app.roadmaxxing.shared.summary

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.domain.DriveStatus
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.shared.privacy.PrivacyClipper
import app.roadmaxxing.shared.privacy.PrivacyZone
import app.roadmaxxing.shared.progress.ProgressCalculator
import app.roadmaxxing.shared.region.Region
import app.roadmaxxing.shared.region.RegionPackSource
import app.roadmaxxing.shared.region.Segment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * OPEN PRODUCT DECISION: a district counts as completed from this percentage on (not 100, because of tiny dead-end
 * roads that are never driven). Change here only.
 */
const val DISTRICT_COMPLETE_PERCENT: Double = 95.0

/**
 * Produces the data of the drive card and the city card. Blocking work (tile decoding, JSON) runs on [dispatcher].
 * [boundarySource] is the region pack source (only `regions.json` is read from it, for the province outline).
 */
class SummaryRepository(
    private val packProvider: RegionPackProvider,
    private val store: DriveStore,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val boundarySource: RegionPackSource? = null,
    /** Privacy zones cut out of every card geometry (after the fixed end trim). Read per card, so edits apply at once. */
    private val privacyZones: () -> List<PrivacyZone> = { emptyList() },
) {
    suspend fun driveCard(driveId: Long, languageCode: String): DriveCardData? = withContext(dispatcher) {
        val record = store.drive(driveId) ?: return@withContext null
        val summary = record.summary
        if (record.status != DriveStatus.Processed || summary == null || summary.verdict != DriveVerdict.Drive) {
            return@withContext null
        }
        val from = record.keepFromMs ?: record.startMs
        val to = record.keepToMs ?: (from + summary.durationMs)
        val points = store.points(driveId, from, to).sortedBy { it.timeMs }
        val speeds = SpeedStats.compute(points)
        val raw = points.map { GeoPoint(it.lat, it.lon) }

        val zones = privacyZones()
        val route = ArrayList<CardPolyline>()
        val trimmed = CardGeometry.trimEnds(raw, PRIVACY_TRIM_M)
        if (trimmed.size >= 2) {
            for (piece in PrivacyClipper.clip(trimmed, zones)) {
                route.add(CardPolyline(CardGeometry.simplify(piece, ROUTE_TOLERANCE_M), fresh = false))
            }
            for (line in freshLines(driveId)) {
                for (trimmedPiece in CardGeometry.clipByRoutePosition(line, raw, PRIVACY_TRIM_M)) {
                    for (piece in PrivacyClipper.clip(trimmedPiece, zones)) {
                        val simple = CardGeometry.simplify(piece, FRESH_TOLERANCE_M)
                        if (simple.size >= 2) route.add(CardPolyline(simple, fresh = true))
                    }
                }
            }
        }
        DriveCardData(
            driveId = driveId,
            startMs = from,
            endMs = to,
            distanceM = summary.distanceM,
            durationMs = summary.durationMs,
            avgSpeedMps = speeds.avgMps,
            maxSpeedMps = speeds.maxMps,
            newLengthM = summary.newLengthDm / 10.0,
            route = route,
            regionName = packProvider.pack.province.localName(languageCode),
        )
    }

    suspend fun cityCard(languageCode: String): CityCardData? = withContext(dispatcher) {
        val pack = packProvider.pack
        val drivenIds = store.drivenSegmentIds()
        val progress = ProgressCalculator(pack).compute(drivenIds)
        val province = progress.firstOrNull { it.regionId == pack.provinceId } ?: return@withContext null
        val districtIds = pack.districts.map { it.id }.toSet()
        val districts = progress.filter { it.regionId in districtIds }
        val weekSince = clock() - WEEK_MS
        var weekDm = 0L
        for (id in store.segmentsFirstDrivenSince(weekSince)) weekDm += pack.summary(id)?.lengthDm ?: 0L

        val zones = privacyZones()
        val driven = ArrayList<List<GeoPoint>>()
        if (drivenIds.isNotEmpty()) {
            for (seg in SegmentResolver(pack, store).resolve(drivenIds, store.drivenSegmentFirstDrives(), scanForMissing = false)) {
                for (piece in PrivacyClipper.clip(seg.toPoints(), zones)) {
                    val simple = CardGeometry.simplify(piece, CITY_TOLERANCE_M)
                    if (simple.size >= 2) driven.add(simple)
                }
            }
        }
        CityCardData(
            regionId = pack.provinceId,
            name = pack.province.localName(languageCode),
            percent = province.percent,
            percentMajor = province.percentMajor,
            percentLocal = province.percentLocal,
            districtsCompleted = districts.count { it.percent >= DISTRICT_COMPLETE_PERCENT },
            districtsTotal = districts.size,
            weekNewLengthM = weekDm / 10.0,
            boundary = readBoundary(pack.provinceId),
            driven = driven,
        )
    }

    /** Geometry of the segments first driven by [driveId], from the tiles around the drive's fixes. */
    private fun freshLines(driveId: Long): List<List<GeoPoint>> {
        val ids = store.segmentsFirstDrivenBy(driveId)
        if (ids.isEmpty()) return emptyList()
        val firstDrives = ids.associateWith { driveId }
        return SegmentResolver(packProvider.pack, store).resolve(ids, firstDrives, scanForMissing = true).map { it.toPoints() }
    }

    /** Simplified outer rings of the province from `regions.json` ("boundary": MultiPolygon, [lon, lat] pairs). */
    private fun readBoundary(regionId: Long): List<List<GeoPoint>> {
        val bytes = boundarySource?.read("regions.json") ?: return emptyList()
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val region = (root["regions"] as? JsonArray)?.map { it.jsonObject }
            ?.firstOrNull { it["id"]?.jsonPrimitive?.longOrNull == regionId } ?: return emptyList()
        val polygons = region["boundary"] as? JsonArray ?: return emptyList()
        val rings = ArrayList<List<GeoPoint>>()
        for (polygon in polygons) {
            val outer = polygon.jsonArray.firstOrNull()?.jsonArray ?: continue
            val pts = outer.mapNotNull { c ->
                val a = c.jsonArray
                val lon = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                val lat = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                if (lon == null || lat == null) null else GeoPoint(lat, lon)
            }
            val simple = CardGeometry.simplify(pts, BOUNDARY_TOLERANCE_M)
            if (simple.size >= 3) rings.add(simple)
        }
        return rings
    }

    private fun Segment.toPoints(): List<GeoPoint> = List(vertexCount) { GeoPoint(lats[it], lons[it]) }

    private fun Region.localName(languageCode: String): String = when (languageCode.lowercase().take(2)) {
        "tr" -> nameTr ?: name
        "en" -> nameEn ?: name
        else -> name
    }

    companion object {
        const val PRIVACY_TRIM_M: Double = 300.0
        const val ROUTE_TOLERANCE_M: Double = 8.0
        const val FRESH_TOLERANCE_M: Double = 5.0
        const val CITY_TOLERANCE_M: Double = 40.0
        const val BOUNDARY_TOLERANCE_M: Double = 150.0
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
