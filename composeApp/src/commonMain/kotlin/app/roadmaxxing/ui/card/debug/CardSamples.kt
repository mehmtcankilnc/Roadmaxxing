package app.roadmaxxing.ui.card.debug

import app.roadmaxxing.shared.summary.CardPolyline
import app.roadmaxxing.shared.summary.CityCardData
import app.roadmaxxing.shared.summary.DriveCardData
import app.roadmaxxing.shared.summary.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Sample data for the debug card preview, including edge cases (long names, zero new km, empty route, 0/15).

private const val LAT = 37.0
private const val LON = 35.32

private fun wavy(n: Int, lon0: Double, lat0: Double, dLon: Double, amp: Double, phase: Double = 0.0): List<GeoPoint> =
    List(n) { i ->
        val t = i / (n - 1.0)
        GeoPoint(lat0 + amp * sin(t * 3 * PI + phase) + 0.004 * cos(t * 9 * PI), lon0 + dLon * t)
    }

private fun blob(seed: Double, radiusLon: Double, radiusLat: Double): List<GeoPoint> = List(90) { i ->
    val a = 2 * PI * i / 90
    val r = 1.0 + 0.22 * sin(3 * a + seed) + 0.12 * cos(5 * a + seed * 2) + 0.06 * sin(9 * a + seed)
    GeoPoint(LAT + radiusLat * r * sin(a), LON + radiusLon * r * cos(a))
}

internal val sampleDriveNormal = DriveCardData(
    driveId = 1, startMs = 1_763_300_000_000, endMs = 1_763_302_412_000,
    distanceM = 18_400.0, durationMs = 2_412_000, avgSpeedMps = 22.3, maxSpeedMps = 31.0, newLengthM = 3_420.0,
    route = listOf(
        CardPolyline(wavy(40, LON, LAT, 0.12, 0.020), fresh = false),
        CardPolyline(wavy(30, LON + 0.12, LAT + 0.01, 0.07, 0.015, 1.0), fresh = true),
        CardPolyline(wavy(25, LON + 0.19, LAT + 0.02, -0.10, 0.012, 2.0), fresh = false),
    ),
    regionName = "Seyhan",
)

internal val sampleDriveEdge = DriveCardData(
    driveId = 2, startMs = 1_763_300_000_000, endMs = 1_763_307_800_000,
    distanceM = 1_234_567.0, durationMs = 7_800_000, avgSpeedMps = 24.0, maxSpeedMps = 38.0, newLengthM = 0.0,
    route = emptyList(),
    regionName = "Çukurova Üniversitesi Balcalı Kampüs Mahallesi",
)

internal val sampleCityNormal = CityCardData(
    regionId = 1, name = "Adana", percent = 12.4, percentMajor = 34.2, percentLocal = 8.0,
    districtsCompleted = 3, districtsTotal = 15, weekNewLengthM = 12_400.0,
    boundary = listOf(blob(0.5, 0.35, 0.25)),
    driven = listOf(
        wavy(50, LON - 0.2, LAT, 0.3, 0.05),
        wavy(40, LON - 0.1, LAT - 0.1, 0.2, 0.04, 1.5),
        wavy(30, LON - 0.05, LAT + 0.08, 0.15, 0.02, 3.0),
    ),
)

internal val sampleCityEdge = CityCardData(
    regionId = 2, name = "Kahramanmaraş-Elbistan-Afşin Bölgesi Merkez İlçesi", percent = 0.18, percentMajor = 0.0, percentLocal = 0.1,
    districtsCompleted = 0, districtsTotal = 15, weekNewLengthM = 40.0,
    boundary = listOf(blob(2.2, 0.22, 0.34)),
    driven = listOf(wavy(8, LON - 0.02, LAT, 0.03, 0.01)),
)
