package app.roadmaxxing.shared.summary

import app.roadmaxxing.shared.geo.Geo
import app.roadmaxxing.shared.model.TracePoint

/** Average (moving) and maximum speed of a drive, both null when the trace is too sparse to tell. */
data class SpeedStats(val avgMps: Double?, val maxMps: Double?) {
    companion object {
        /** Half width of the window used to tell "standing still" from GPS jitter. */
        const val STILL_WINDOW_S: Double = 5.0

        /**
         * Standing still is decided from the displacement over a +-[STILL_WINDOW_S] window: jitter of a parked phone
         * (several m/s per 1 s step) cancels out there. Without such a window the filtered step speed is used.
         */
        private fun isMoving(p: List<TracePoint>, k: Int, stepSpeed: Double): Boolean {
            val t = p[k].timeMs
            var a = k
            while (a > 0 && t - p[a - 1].timeMs <= STILL_WINDOW_S * 1000) a--
            var b = k
            while (b < p.size - 1 && p[b + 1].timeMs - t <= STILL_WINDOW_S * 1000) b++
            val span = (p[b].timeMs - p[a].timeMs) / 1000.0
            if (span < STILL_WINDOW_S) return stepSpeed > MOVING_MPS
            val d = Geo.distanceM(p[a].lat, p[a].lon, p[b].lat, p[b].lon)
            return d / span > STILL_MPS
        }

        const val STILL_MPS: Double = 1.0

        /** Steps longer than this are gaps (dropouts, tunnels) and are ignored. */
        const val MAX_STEP_S: Double = 15.0

        /** A filtered step slower than this counts as standing still (same rule as `avg_moving` in the test data). */
        const val MOVING_MPS: Double = 0.5
        const val MEDIAN_WINDOW: Int = 5
        const val MIN_STEPS: Int = 5

        /** Reported speeds above this (m/s, 252 km/h) are treated as invalid. */
        const val MAX_PLAUSIBLE_MPS: Double = 70.0

        /** Largest believable change of speed per second (m/s^2); the filtered series is slew limited to it. */
        const val MAX_ACCEL_MPS2: Double = 10.0

        /** Share of steps that must carry a usable reported speed before the reported series is trusted. */
        const val MIN_REPORTED_SHARE: Double = 0.8

        /**
         * Per step the speed is the platform's reported (GNSS Doppler) speed of the step's first fix when it is present,
         * plausible (0..[MAX_PLAUSIBLE_MPS]) and consistent with the position track (within max(4 m/s, 50 %) of the
         * speed over a +-[STILL_WINDOW_S] displacement window); otherwise it is derived from the two positions.
         * Then a [MEDIAN_WINDOW]-sample running median removes isolated outliers (one bad value corrupts at most two
         * neighbouring steps, never a window majority) and a slew limit of [MAX_ACCEL_MPS2] removes remaining jumps.
         * Derived speeds are noisy (1 s steps on jittery fixes), so for them a short moving average follows; reported
         * speeds are already accurate and are not smoothed further (that would only shave real peaks).
         *
         * Average = moving distance / moving time (standing still: reported speed <= [MOVING_MPS], or for derived speeds [isMoving]); stops in traffic do not drag
         * it down. Maximum = highest filtered step. The average is a time weighted mean of values all <= the maximum,
         * so avg <= max always holds (also clamped as a guard).
         */
        fun compute(points: List<TracePoint>): SpeedStats {
            val sorted = points.sortedBy { it.timeMs }
            val dt = ArrayList<Double>()
            val v = ArrayList<Double>()
            val at = ArrayList<Int>() // index of the step's first point
            var reported = 0
            for (i in 1 until sorted.size) {
                val a = sorted[i - 1]
                val b = sorted[i]
                val s = (b.timeMs - a.timeMs) / 1000.0
                if (s <= 0.0 || s > MAX_STEP_S) continue
                val derived = Geo.distanceM(a.lat, a.lon, b.lat, b.lon) / s
                val rep = a.speedMps
                var value = derived
                if (rep != null && rep >= 0.0 && rep <= MAX_PLAUSIBLE_MPS) {
                    val ref = windowSpeed(sorted, i - 1)
                    if (ref == null || kotlin.math.abs(rep - ref) <= maxOf(4.0, 0.5 * ref)) {
                        value = rep
                        reported++
                    }
                }
                dt.add(s)
                at.add(i - 1)
                v.add(value)
            }
            if (v.size < MIN_STEPS) return SpeedStats(null, null)
            val useReported = reported >= MIN_REPORTED_SHARE * v.size
            if (!useReported) {
                // Too few usable reported speeds to mix: derive everything from positions.
                for (k in v.indices) {
                    val a = sorted[at[k]]
                    val b = sorted[at[k] + 1]
                    v[k] = Geo.distanceM(a.lat, a.lon, b.lat, b.lon) / dt[k]
                }
            }
            val half = MEDIAN_WINDOW / 2
            val f = DoubleArray(v.size) { i ->
                val window = v.subList(maxOf(0, i - half), minOf(v.size - 1, i + half) + 1).sorted()
                window[window.size / 2]
            }
            for (i in 1 until f.size) {
                val limit = MAX_ACCEL_MPS2 * dt[i]
                f[i] = f[i].coerceIn(f[i - 1] - limit, f[i - 1] + limit)
            }
            val g = if (useReported) f else DoubleArray(f.size) { i ->
                var sum = 0.0
                var n = 0
                for (j in maxOf(0, i - half)..minOf(f.size - 1, i + half)) {
                    sum += f[j]
                    n++
                }
                sum / n
            }
            var dist = 0.0
            var time = 0.0
            for (i in g.indices) {
                if (if (useReported) g[i] > MOVING_MPS else isMoving(sorted, at[i], g[i])) {
                    dist += g[i] * dt[i]
                    time += dt[i]
                }
            }
            if (time <= 0.0) return SpeedStats(null, g.max())
            val max = g.max()
            val avg = minOf(dist / time, max)
            return SpeedStats(avg, max)
        }

        /** Speed from the displacement over a +-[STILL_WINDOW_S] window around point [k], or null when the window is too short. */
        private fun windowSpeed(p: List<TracePoint>, k: Int): Double? {
            val t = p[k].timeMs
            var a = k
            while (a > 0 && t - p[a - 1].timeMs <= STILL_WINDOW_S * 1000) a--
            var b = k
            while (b < p.size - 1 && p[b + 1].timeMs - t <= STILL_WINDOW_S * 1000) b++
            val span = (p[b].timeMs - p[a].timeMs) / 1000.0
            if (span < STILL_WINDOW_S) return null
            return Geo.distanceM(p[a].lat, p[a].lon, p[b].lat, p[b].lon) / span
        }
    }
}
