package app.roadmaxxing.shared.testdata

import app.roadmaxxing.shared.model.TracePoint
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Re-creates the noise model of test-data/gpx/README.md ("Track format") with other random seeds,
 * so the matcher can be checked on noise realisations it was not tuned on:
 * Gauss-Markov offset (sigma 6 m per axis, correlation time 30 s) + 1.5 m white jitter,
 * speed +-0.3 m/s, course +-4 deg, about one dropout per 150 s removing 2-5 consecutive points.
 */
object SyntheticNoise {
    fun apply(clean: List<TracePoint>, seed: Int, keepSpeedAndCourse: Boolean = true): List<TracePoint> {
        val rnd = Random(seed)
        fun gauss(): Double {
            // Box-Muller
            val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
            val u2 = rnd.nextDouble()
            return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * Math.PI * u2)
        }
        val sigma = 6.0
        val tau = 30.0
        val jitter = 1.5
        var ox = gauss() * sigma
        var oy = gauss() * sigma
        val out = ArrayList<TracePoint>(clean.size)
        var skip = 0
        for ((i, p) in clean.withIndex()) {
            if (i > 0) {
                val dt = (p.timeMs - clean[i - 1].timeMs) / 1000.0
                val a = exp(-dt / tau)
                val s = sigma * sqrt(1 - a * a)
                ox = ox * a + s * gauss()
                oy = oy * a + s * gauss()
            }
            val ex = ox + jitter * gauss()
            val ey = oy + jitter * gauss()
            if (skip > 0) {
                skip--
                continue
            }
            if (i > 0 && i < clean.size - 1 && rnd.nextDouble() < 1.0 / 150.0) {
                skip = 1 + rnd.nextInt(4) // this point + 1..4 more = 2..5 dropped
                continue
            }
            val mPerDegLat = 111_132.0
            val mPerDegLon = 111_320.0 * cos(Math.toRadians(p.lat))
            out.add(
                TracePoint(
                    timeMs = p.timeMs,
                    lat = p.lat + ey / mPerDegLat,
                    lon = p.lon + ex / mPerDegLon,
                    speedMps = if (keepSpeedAndCourse) p.speedMps?.let { (it + 0.3 * gauss()).coerceAtLeast(0.0) } else null,
                    bearingDeg = if (keepSpeedAndCourse) p.bearingDeg?.let { ((it + 4.0 * gauss()) % 360.0 + 360.0) % 360.0 } else null,
                    accuracyM = null,
                ),
            )
        }
        return out
    }
}
