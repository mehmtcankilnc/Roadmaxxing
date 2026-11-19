package app.roadmaxxing.shared.settings

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.domain.DriveRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.round
import kotlin.time.Instant

data class ExportSummary(val fileName: String, val driveCount: Int)

/**
 * Exports every stored drive (counted or not, every stored trace point) as one GPX 1.1 document, one `<trk>` per
 * drive, streamed in chunks. Everything is metric and UTC: this is a data file, not UI.
 *
 * Memory: one point is ~85 characters, so a 1-hour drive at 1 Hz (3,600 points) is ~300 KB of text. Only one
 * drive's points and one chunk (at most [POINTS_PER_CHUNK] points, ~170 KB) are alive at a time, however many
 * drives there are.
 */
class DataExporter(
    private val store: DriveStore,
    private val dispatcher: CoroutineDispatcher,
) {
    /**
     * Writes GPX 1.1 to [out] in chunks (header, then one chunk per drive or per ~2,000 points, then footer).
     * Returns null and writes nothing when there are no drives. Off the main thread. The [out] callback runs on
     * the export dispatcher; the chunk it receives is reused afterwards, so it must copy or write it right away.
     */
    suspend fun exportGpx(nowMs: Long, out: (CharSequence) -> Unit): ExportSummary? = withContext(dispatcher) {
        val drives = store.observeDrives().first().sortedWith(compareBy<DriveRecord> { it.startMs }.thenBy { it.id })
        if (drives.isEmpty()) return@withContext null
        val sb = StringBuilder(CHUNK_CAPACITY)
        fun flush() {
            if (sb.isEmpty()) return
            out(sb)
            sb.setLength(0)
        }
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Roadmaxxing\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata><time>").append(isoTime(nowMs)).append("</time></metadata>\n")
        flush()
        for (drive in drives) {
            sb.append("  <trk>\n    <name>").append(escape("Drive ${drive.id} ${isoTime(drive.startMs)}")).append("</name>\n")
            sb.append("    <desc>").append(escape(describe(drive))).append("</desc>\n")
            sb.append("    <trkseg>\n")
            var inChunk = 0
            for (p in store.points(drive.id, Long.MIN_VALUE, Long.MAX_VALUE).sortedBy { it.timeMs }) {
                sb.append("      <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\"><time>")
                    .append(isoTime(p.timeMs)).append("</time></trkpt>\n")
                if (++inChunk >= POINTS_PER_CHUNK) {
                    flush()
                    inChunk = 0
                }
            }
            sb.append("    </trkseg>\n  </trk>\n")
            flush()
        }
        sb.append("</gpx>\n")
        flush()
        ExportSummary("roadmaxxing-export-${isoTime(nowMs).substring(0, 10)}.gpx", drives.size)
    }

    private fun describe(d: DriveRecord): String {
        val s = d.summary ?: return "status: ${d.status.name.lowercase()}"
        return "verdict: ${s.verdict.name}; distance: ${round2(s.distanceM / 1000.0)} km; " +
            "new roads: ${round2(s.newLengthDm / 10_000.0)} km"
    }

    private fun round2(v: Double): String {
        val r = round(v * 100).toLong()
        return "${r / 100}.${(r % 100).toString().padStart(2, '0')}"
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private fun isoTime(ms: Long): String = Instant.fromEpochMilliseconds(ms).toString()

    private companion object {
        const val POINTS_PER_CHUNK = 2_000
        const val CHUNK_CAPACITY = 256 * 1024
    }
}
