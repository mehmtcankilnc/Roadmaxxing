package app.roadmaxxing.notify

import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.notification_result_body_new
import app.roadmaxxing.resources.notification_result_body_none
import app.roadmaxxing.resources.notification_result_title
import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.drive.DriveVerdict
import app.roadmaxxing.ui.main.formatDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString

/** Platform side of the end-of-drive notification. */
interface DriveResultPoster {
    /** Called once at app start, before any notification can be tapped (iOS sets its delegate here). */
    fun install() {}

    /** True when the user allows notifications for this app right now. */
    suspend fun canPost(): Boolean

    /** Shows one notification; tapping it must call [PendingDriveNavigation.open] with [driveId]. */
    suspend fun post(driveId: Long, title: String, body: String)
}

/**
 * Posts one local "drive saved" notification per processed drive that counted as a drive. Silent when the
 * permission is missing. The text carries distance and new kilometres only, never speed.
 */
class DriveResultNotifier(
    private val store: DriveStore,
    private val poster: DriveResultPoster,
) {
    private val mutex = Mutex()
    private val handled = mutableSetOf<Long>()

    fun install() = poster.install()

    /** [driveIds] are the drives that finished processing. Never throws: a notification must not break tracking. */
    suspend fun onDrivesProcessed(driveIds: List<Long>) {
        if (driveIds.isEmpty()) return
        runCatching {
            mutex.withLock {
                val fresh = driveIds.filter { it !in handled }
                if (fresh.isEmpty() || !poster.canPost()) return@withLock
                for (id in fresh) {
                    handled += id
                    val summary = withContext(Dispatchers.Default) { store.drive(id)?.summary } ?: continue
                    if (summary.verdict != DriveVerdict.Drive) continue
                    val separator = getString(Res.string.decimal_separator)
                    val km = formatDecimal(summary.distanceM / 1000.0, 1, separator)
                    val body = if (summary.newLengthDm >= MIN_NEW_DM) {
                        getString(Res.string.notification_result_body_new, km, formatDecimal(summary.newLengthDm / 10_000.0, 1, separator))
                    } else {
                        getString(Res.string.notification_result_body_none, km)
                    }
                    poster.post(id, getString(Res.string.notification_result_title), body)
                }
            }
        }
    }

    private companion object {
        /** Below 50 m (500 dm) of new road the text says there was none. */
        const val MIN_NEW_DM = 500L
    }
}
