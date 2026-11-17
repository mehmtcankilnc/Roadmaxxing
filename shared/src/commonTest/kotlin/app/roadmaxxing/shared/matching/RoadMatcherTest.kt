package app.roadmaxxing.shared.matching

import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.region.FixturePack
import app.roadmaxxing.shared.region.FixturePack.id
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Matching on the embedded fixture pack (map in test-data/regions/fixture/README.md). */
class RoadMatcherTest {
    // Fixture node coordinates (lon, lat).
    private val n = mapOf(
        101L to (35.002 to 37.002), 102L to (35.006 to 37.002),
        111L to (35.002 to 37.005), 112L to (35.006 to 37.005), 113L to (35.010 to 37.005),
        114L to (35.014 to 37.005), 115L to (35.018 to 37.005),
        121L to (35.002 to 37.008), 123L to (35.014 to 37.008), 124L to (35.018 to 37.008),
    )

    /** 1 Hz trace along the given nodes at constant speed, optionally without speed/bearing (emulator). */
    private fun drive(
        nodes: List<Long>,
        speed: Double = 10.0,
        withCourse: Boolean = true,
        stopAtNodeIndex: Int = -1,
        stopSeconds: Int = 0,
    ): List<TracePoint> {
        val out = ArrayList<TracePoint>()
        var t = 0L
        for (k in 0 until nodes.size - 1) {
            val (lon1, lat1) = n.getValue(nodes[k])
            val (lon2, lat2) = n.getValue(nodes[k + 1])
            val d = Geo.distanceM(lat1, lon1, lat2, lon2)
            val bearing = Geo.bearingDeg(lat1, lon1, lat2, lon2)
            val steps = ceil(d / speed).toInt()
            if (k == stopAtNodeIndex) {
                repeat(stopSeconds) { i ->
                    // stationary jitter of a few metres
                    val j = ((i % 5) - 2) * 0.00002
                    out.add(TracePoint(t++ * 1000, lat1 + j, lon1 - j, if (withCourse) 0.0 else null, null, null))
                }
            }
            for (s in 0 until steps) {
                val f = s.toDouble() / steps
                out.add(
                    TracePoint(
                        t++ * 1000, lat1 + (lat2 - lat1) * f, lon1 + (lon2 - lon1) * f,
                        if (withCourse) speed else null, if (withCourse) bearing else null, null,
                    ),
                )
            }
        }
        val (lon, lat) = n.getValue(nodes.last())
        out.add(TracePoint(t * 1000, lat, lon, if (withCourse) 0.0 else null, null, null))
        return out
    }

    private fun drivenIds(trace: List<TracePoint>): Set<Long> =
        RoadMatcher(FixturePack.pack()).match(trace).driven.map { it.segmentId }.toSet()

    @Test
    fun straightPrimaryRoad() {
        val r = RoadMatcher(FixturePack.pack()).match(drive(listOf(111, 112, 113, 114, 115)))
        assertEquals(setOf(id(1001, 0), id(1001, 1), id(1001, 2)), r.driven.map { it.segmentId }.toSet())
        assertEquals(1.0, r.matchedPointFraction, 1e-9)
        assertEquals(1424.0, r.matchedDistanceM, 15.0) // 4 x 356 m
        val m = r.driven.first { it.segmentId == id(1001, 2) }
        assertEquals(12L, m.districtId)
        assertEquals(3560L, m.lengthDm)
        assertTrue(m.coverage > 0.99)
    }

    @Test
    fun turnsWithoutSpeedOrBearing() {
        val ids = drivenIds(drive(listOf(101, 111, 121, 123), speed = 8.0, withCourse = false))
        assertEquals(setOf(id(2001, 0), id(2001, 1), id(2005, 0)), ids)
    }

    @Test
    fun onewayIsRespected() {
        // 1003 is oneway 123 -> 124: driving it backwards does not paint it.
        assertTrue(id(1003, 0) !in drivenIds(drive(listOf(124, 123))))
        assertTrue(id(1003, 0) in drivenIds(drive(listOf(123, 124))))
        // 2003 is oneway=-1 (geometry 114 -> 123, travel only 123 -> 114).
        assertTrue(id(2003, 0) in drivenIds(drive(listOf(123, 114))))
    }

    @Test
    fun partialSegmentIsNotDriven() {
        val (lon1, lat1) = n.getValue(111)
        val trace = (0..17).map { TracePoint(it * 1000L, lat1, lon1 + 0.002 * it / 17.0, 10.0, 90.0, null) } // half of 1001:0
        val r = RoadMatcher(FixturePack.pack()).match(trace)
        assertTrue(r.driven.isEmpty())
        val p = r.partial.single { it.segmentId == id(1001, 0) }
        assertEquals(0.5, p.coverage, 0.05)
    }

    @Test
    fun stopsDoNotChangeTheResult() {
        val ids = drivenIds(drive(listOf(111, 112, 113, 114, 115), stopAtNodeIndex = 2, stopSeconds = 240))
        assertEquals(setOf(id(1001, 0), id(1001, 1), id(1001, 2)), ids)
    }

    @Test
    fun dropoutsAreBridgedAlongTheNetwork() {
        // Keep every 6th point only (5 s gaps): segments in between are still covered by the network route.
        val trace = drive(listOf(101, 111, 112, 113, 114), speed = 12.0).filterIndexed { i, _ -> i % 6 == 0 }
        assertEquals(setOf(id(2001, 0), id(1001, 0), id(1001, 1)), drivenIds(trace) - id(1001, 2))
    }

    @Test
    fun traceAwayFromRoadsMatchesNothing() {
        val trace = (0..120).map { TracePoint(it * 1000L, 36.990, 35.000 + it * 0.0001, 20.0, 90.0, null) }
        val r = RoadMatcher(FixturePack.pack()).match(trace)
        assertTrue(r.driven.isEmpty())
        assertEquals(0.0, r.matchedPointFraction)
    }

    @Test
    fun tinyTracesAreEmpty() {
        val m = RoadMatcher(FixturePack.pack())
        assertEquals(MatchResult.EMPTY, m.match(emptyList()))
        assertEquals(MatchResult.EMPTY, m.match(listOf(TracePoint(0, 37.005, 35.003, null, null, null))))
    }

    @Test
    fun unionLengthMergesOverlaps() {
        val l = RoadMatcher.unionLength(
            listOf(doubleArrayOf(0.0, 10.0), doubleArrayOf(5.0, 20.0), doubleArrayOf(30.0, 40.0), doubleArrayOf(-5.0, 2.0), doubleArrayOf(95.0, 120.0)),
            100.0,
        )
        assertEquals(20.0 + 10.0 + 5.0, l, 1e-9)
        assertEquals(0.0, RoadMatcher.unionLength(emptyList(), 10.0))
    }
}
