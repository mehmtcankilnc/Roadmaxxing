package app.roadmaxxing.notify

import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.card_percent_leading
import app.roadmaxxing.resources.card_percent_symbol
import app.roadmaxxing.resources.decimal_separator
import app.roadmaxxing.resources.notification_milestone_district
import app.roadmaxxing.resources.notification_milestone_districts
import app.roadmaxxing.resources.notification_milestone_province
import app.roadmaxxing.resources.notification_milestone_with_new
import app.roadmaxxing.resources.notification_result_body_new
import app.roadmaxxing.resources.notification_result_body_none
import app.roadmaxxing.resources.notification_result_title
import app.roadmaxxing.resources.unit_km
import app.roadmaxxing.resources.unit_mi
import app.roadmaxxing.shared.milestones.MilestoneEvent
import app.roadmaxxing.shared.milestones.MilestoneRepository
import app.roadmaxxing.shared.settings.DistanceUnit
import app.roadmaxxing.shared.settings.SettingsRepository
import app.roadmaxxing.shared.units.isQuietAt
import app.roadmaxxing.shared.units.metersIn
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
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
 * permission is missing (milestones are still evaluated). The text carries distance and new road length only, never speed.
 */
class DriveResultNotifier(
    private val store: DriveStore,
    private val poster: DriveResultPoster,
    private val settings: SettingsRepository,
    private val milestones: MilestoneRepository,
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
                if (fresh.isEmpty()) return@withLock
                // Milestones are evaluated for every counted drive, even when nothing will be shown, so the
                // celebration is queued whether or not the notification is allowed or quiet.
                val canPost = poster.canPost()
                for (id in fresh) {
                    handled += id
                    val summary = withContext(Dispatchers.Default) { store.drive(id)?.summary } ?: continue
                    if (summary.verdict != DriveVerdict.Drive) continue
                    val events = runCatching { milestones.evaluate(id) }.getOrDefault(emptyList())
                    // Quiet hours: the drive counts as handled, but nothing is shown (now or later).
                    if (!canPost || isQuietAt(settings.quietHours.value, nowMs(), TimeZone.currentSystemDefault())) continue
                    val separator = getString(Res.string.decimal_separator)
                    val unit = settings.distanceUnit.value
                    val unitLabel = getString(if (unit == DistanceUnit.Miles) Res.string.unit_mi else Res.string.unit_km)
                    val distance = formatDecimal(metersIn(summary.distanceM, unit), 1, separator)
                    val hasNew = summary.newLengthDm >= MIN_NEW_DM
                    val newText = formatDecimal(metersIn(summary.newLengthDm / 10.0, unit), 1, separator)
                    val lead = milestoneLead(events)
                    val body = when {
                        lead != null && hasNew ->
                            getString(Res.string.notification_milestone_with_new, lead, newText, unitLabel)
                        lead != null -> lead
                        hasNew -> getString(Res.string.notification_result_body_new, distance, newText, unitLabel)
                        else -> getString(Res.string.notification_result_body_none, distance, unitLabel)
                    }
                    poster.post(id, getString(Res.string.notification_result_title), body)
                }
            }
        }
    }

    /** First part of the body when a milestone was reached: the province threshold wins over districts. */
    private suspend fun milestoneLead(events: List<MilestoneEvent>): String? {
        val province = events.filterIsInstance<MilestoneEvent.ProvinceThreshold>().maxByOrNull { it.percent }
        val districts = events.filterIsInstance<MilestoneEvent.DistrictCompleted>()
        return when {
            province != null -> {
                val symbol = getString(Res.string.card_percent_symbol)
                val leading = getString(Res.string.card_percent_leading) == "1"
                val percent = province.percent.toString().let { if (leading) "$symbol$it" else "$it$symbol" }
                getString(Res.string.notification_milestone_province, percent, province.name)
            }
            districts.size == 1 -> getString(Res.string.notification_milestone_district, districts.first().name)
            districts.size > 1 -> getString(Res.string.notification_milestone_districts, districts.size.toString())
            else -> null
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        /** Below 50 m (500 dm) of new road the text says there was none. */
        const val MIN_NEW_DM = 500L
    }
}
