package app.roadmaxxing.shared.testdata

import app.roadmaxxing.shared.model.TracePoint
import app.roadmaxxing.shared.region.RegionPackSource
import java.io.File
import java.time.Instant

/** Locates repository test data from JVM host tests. */
object TestData {
    /** Walks up from the working directory until a folder containing both `settings.gradle.kts` and `test-data/` is found. */
    val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "test-data").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("repository root not found above ${System.getProperty("user.dir")}")
    }

    fun file(relative: String): File = File(repoRoot, relative)

    fun packSource(packDir: String): RegionPackSource {
        val root = file(packDir)
        require(root.isDirectory) { "pack folder missing: $root" }
        return RegionPackSource { path ->
            val f = File(root, path)
            if (f.isFile) f.readBytes() else null
        }
    }

    private val trkpt = Regex("""<trkpt lat="([-0-9.]+)" lon="([-0-9.]+)">(.*?)</trkpt>""")
    private val time = Regex("""<time>([^<]+)</time>""")
    private val speed = Regex("""<gpxtpx:speed>([-0-9.]+)</gpxtpx:speed>""")
    private val course = Regex("""<gpxtpx:course>([-0-9.]+)</gpxtpx:course>""")

    /**
     * Parses a test GPX file. With [withSpeedAndCourse] = false the speed/course extensions are dropped,
     * which is what the Android emulator delivers for bearing (and a worst case for speed).
     */
    fun readGpx(name: String, withSpeedAndCourse: Boolean = true): List<TracePoint> {
        val text = file("test-data/gpx/$name").readText()
        return trkpt.findAll(text).map { m ->
            val body = m.groupValues[3]
            TracePoint(
                timeMs = Instant.parse(time.find(body)!!.groupValues[1]).toEpochMilli(),
                lat = m.groupValues[1].toDouble(),
                lon = m.groupValues[2].toDouble(),
                speedMps = if (withSpeedAndCourse) speed.find(body)?.groupValues?.get(1)?.toDouble() else null,
                bearingDeg = if (withSpeedAndCourse) course.find(body)?.groupValues?.get(1)?.toDouble() else null,
                accuracyM = null,
            )
        }.toList()
    }
}
