package app.roadmaxxing.shared.drive

import app.roadmaxxing.shared.model.TracePoint
import kotlin.math.max

/** Activity classes reported by the platform's motion/activity recognition. */
enum class ActivityType {
    InVehicle,

    /** Device is not moving. Inside a car (traffic light, jam) this is NOT "out of the vehicle". */
    Still,

    /** Walking, running or generic on-foot. */
    OnFoot,
    OnBicycle,
    Unknown,
    ;

    /** True for activities that prove the user has left the vehicle. */
    val isOutOfVehicle: Boolean get() = this == OnFoot || this == OnBicycle
}

/** Tunable thresholds of [DriveDetector]. Defaults are the rules approved for Phase 0. */
data class DriveDetectorConfig(
    /**
     * A sample at or above this speed counts as "vehicle speed" for the speed start path.
     * 6.0 m/s = 21.6 km/h: four times walking pace (~1.4 m/s), above jogging (2.5-3.5 m/s) and above
     * the 1.5-2 m/s position-derived speed noise of a standing phone. Urban driving outside stops
     * is 25-60 km/h, so every car trip crosses it within its first minute.
     */
    val vehicleSpeedMps: Double = 6.0,
    /** How many vehicle-speed samples the start window needs (one GPS glitch never starts a drive). */
    val startMinFastSamples: Int = 5,
    /** Sliding window for the speed start path. */
    val startWindowMs: Long = 60_000,
    /** The window must also show this much real displacement (guards against noisy speed fields). */
    val startMinDisplacementM: Double = 150.0,
    /** At or above this speed the vehicle is "moving"; below it is stationary (a stop, or walking away). */
    val movingSpeedMps: Double = 2.5,
    /** Stationary time that ends a drive when a non-vehicle activity was seen. */
    val stillEndWithActivityMs: Long = 3 * 60_000,
    /**
     * Stationary time that ends a drive when no vehicle activity was seen during it: no activity signal at all
     * (no permission, emulator) or only STILL/UNKNOWN (the emulator's motionless sensors report STILL).
     */
    val stillEndWithoutActivityMs: Long = 5 * 60_000,
    /**
     * IN_VEHICLE was seen during the drive and the user never left the car (only STILL since): a long jam is not
     * split before this. Also the safety net for a phone left in a parked car; without it GPS would stay on.
     */
    val stillEndFallbackMs: Long = 15 * 60_000,
    /** Fixes worse than this are dropped. */
    val maxAccuracyM: Double = 50.0,
    /** Idle fixes kept so the part of the drive before the start decision is not lost. */
    val preStartBufferMs: Long = 2 * 60_000,
    /** Speeds above this are treated as GPS glitches when derived from positions. */
    val maxPlausibleSpeedMps: Double = 90.0,
    /**
     * Baseline for speeds derived from positions. Over 1 s, 1.5-2 m of position jitter alone reads as 2-3 m/s
     * for a phone lying still; over 10 s it shrinks to ~0.3 m/s.
     */
    val derivedSpeedBaselineMs: Long = 10_000,
)

/** What the platform layer must do after feeding one input to [DriveDetector]. */
sealed interface DriveEvent {
    /** Start high-accuracy recording. [initialPoints] are buffered fixes that belong to this drive. */
    data class DriveStarted(val startTimeMs: Long, val reason: StartReason, val initialPoints: List<TracePoint>) : DriveEvent

    /** Store this fix as part of the current drive. */
    data class PointAccepted(val point: TracePoint) : DriveEvent

    /** The fix was not stored ([reason] for diagnostics). */
    data class PointDropped(val point: TracePoint, val reason: DropReason) : DriveEvent

    /**
     * Stop recording. Only points with `timeMs` in [keepFromMs]..[keepToMs] belong to the drive; the stationary
     * head and tail outside that range must be trimmed.
     */
    data class DriveEnded(val decisionTimeMs: Long, val keepFromMs: Long, val keepToMs: Long, val reason: EndReason) : DriveEvent
}

enum class StartReason { Activity, Speed, Manual }
enum class EndReason { StillWithOutOfVehicleActivity, StillWithoutVehicleSignal, StillFallback, Manual }
enum class DropReason { NotRecording, PoorAccuracy, OutOfOrder }

/**
 * Pure drive detection state machine. Not thread safe: feed it from one thread/coroutine.
 *
 * Start: an IN_VEHICLE activity, or sustained vehicle speed ([DriveDetectorConfig.vehicleSpeedMps]).
 * End: stationary for 3 min AND an out-of-vehicle activity (on foot, bicycle) arrived during the drive; STILL
 * does not count. Without a vehicle activity during the drive (no signal, or only STILL): stationary for 5 min.
 * With IN_VEHICLE seen and no out-of-vehicle activity: stationary for 15 min. Short traffic stops never split.
 * Missing speeds are derived from consecutive positions.
 */
class DriveDetector(private val config: DriveDetectorConfig = DriveDetectorConfig()) {

    private var recording = false
    private var lastActivity: ActivityType? = null
    private var lastPoint: TracePoint? = null

    // Last fixes within the derived-speed baseline.
    private val recent = ArrayDeque<TracePoint>()

    // Idle: recent fixes for the speed start path and the pre-start buffer.
    private val idleBuffer = ArrayDeque<TracePoint>()

    // Recording state.
    private var driveStartMs = 0L
    private var firstMovingMs: Long? = null
    private var lastMovingMs = 0L

    // Activities received during the current drive (activities from before the start are stale).
    private var driveActivity: ActivityType? = null
    private var vehicleSeenInDrive = false

    val isRecording: Boolean get() = recording

    /** Last activity reported, or null if the platform never delivered one. */
    val lastActivityType: ActivityType? get() = lastActivity

    /** Restores a drive that was recording when the process died. [lastPointTimeMs] = newest stored fix. */
    fun resumeRecording(startTimeMs: Long, lastPointTimeMs: Long) {
        recording = true
        driveStartMs = startTimeMs
        firstMovingMs = startTimeMs
        lastMovingMs = max(startTimeMs, lastPointTimeMs)
        driveActivity = null
        vehicleSeenInDrive = false
        idleBuffer.clear()
    }

    fun onActivity(type: ActivityType, timeMs: Long): List<DriveEvent> {
        lastActivity = type
        if (!recording) {
            return if (type == ActivityType.InVehicle) listOf(start(timeMs, StartReason.Activity)) else emptyList()
        }
        driveActivity = type
        if (type == ActivityType.InVehicle) vehicleSeenInDrive = true
        return checkEnd(timeMs)
    }

    fun onLocation(raw: TracePoint): List<DriveEvent> {
        val prev = lastPoint
        if (prev != null && raw.timeMs <= prev.timeMs) return listOf(DriveEvent.PointDropped(raw, DropReason.OutOfOrder))
        val acc = raw.accuracyM
        if (acc != null && acc > config.maxAccuracyM) return listOf(DriveEvent.PointDropped(raw, DropReason.PoorAccuracy))

        val point = if (raw.speedMps == null && prev != null) raw.copy(speedMps = derivedSpeed(raw)) else raw
        lastPoint = point
        recent.addLast(point)
        while (recent.size > 1 && point.timeMs - recent.first().timeMs > config.derivedSpeedBaselineMs) recent.removeFirst()
        val speed = point.speedMps ?: 0.0

        if (!recording) {
            idleBuffer.addLast(point)
            while (idleBuffer.isNotEmpty() && point.timeMs - idleBuffer.first().timeMs > config.preStartBufferMs) {
                idleBuffer.removeFirst()
            }
            if (!speedStartReached(point.timeMs)) return listOf(DriveEvent.PointDropped(point, DropReason.NotRecording))
            val started = start(point.timeMs, StartReason.Speed)
            return listOf(started)
        }

        val events = mutableListOf<DriveEvent>(DriveEvent.PointAccepted(point))
        if (speed >= config.movingSpeedMps) {
            if (firstMovingMs == null) firstMovingMs = point.timeMs
            lastMovingMs = point.timeMs
        }
        events += checkEnd(point.timeMs)
        return events
    }

    /** Periodic clock tick: ends a drive even when fixes stop arriving (garage, GPS off). */
    fun onTick(nowMs: Long): List<DriveEvent> = checkEnd(nowMs)

    fun manualStart(timeMs: Long): List<DriveEvent> = if (recording) emptyList() else listOf(start(timeMs, StartReason.Manual))

    fun manualStop(timeMs: Long): List<DriveEvent> = if (!recording) emptyList() else listOf(end(timeMs, EndReason.Manual))

    private fun start(timeMs: Long, reason: StartReason): DriveEvent.DriveStarted {
        // Keep buffered fixes from the last stationary moment on: they are the start of this drive.
        val buffered = idleBuffer.toList()
        val lastStill = buffered.indexOfLast { (it.speedMps ?: 0.0) < config.movingSpeedMps && it.timeMs < timeMs }
        val initial = if (reason == StartReason.Speed) buffered.drop(max(0, lastStill)) else buffered.takeLast(1)
        idleBuffer.clear()
        recording = true
        driveStartMs = initial.firstOrNull()?.timeMs ?: timeMs
        val moving = initial.filter { (it.speedMps ?: 0.0) >= config.movingSpeedMps }
        firstMovingMs = moving.firstOrNull()?.timeMs
        // A drive started by activity counts as "moving" from its start, so the end timers run from there.
        lastMovingMs = moving.lastOrNull()?.timeMs ?: timeMs
        vehicleSeenInDrive = reason == StartReason.Activity
        driveActivity = if (reason == StartReason.Activity) ActivityType.InVehicle else null
        return DriveEvent.DriveStarted(driveStartMs, reason, initial)
    }

    private fun checkEnd(nowMs: Long): List<DriveEvent> {
        if (!recording) return emptyList()
        val still = nowMs - lastMovingMs
        val outOfVehicle = driveActivity?.isOutOfVehicle == true
        val reason = when {
            outOfVehicle && still >= config.stillEndWithActivityMs -> EndReason.StillWithOutOfVehicleActivity
            !outOfVehicle && !vehicleSeenInDrive && still >= config.stillEndWithoutActivityMs -> EndReason.StillWithoutVehicleSignal
            still >= config.stillEndFallbackMs -> EndReason.StillFallback
            else -> return emptyList()
        }
        return listOf(end(nowMs, reason))
    }

    private fun end(nowMs: Long, reason: EndReason): DriveEvent.DriveEnded {
        val from = firstMovingMs ?: driveStartMs
        val to = if (reason == EndReason.Manual) nowMs else lastMovingMs
        recording = false
        firstMovingMs = null
        idleBuffer.clear()
        return DriveEvent.DriveEnded(decisionTimeMs = nowMs, keepFromMs = from, keepToMs = max(from, to), reason = reason)
    }

    private fun speedStartReached(nowMs: Long): Boolean {
        val window = idleBuffer.filter { nowMs - it.timeMs <= config.startWindowMs }
        val fast = window.count { (it.speedMps ?: 0.0) >= config.vehicleSpeedMps }
        if (fast < config.startMinFastSamples) return false
        return distanceM(window.first(), window.last()) >= config.startMinDisplacementM
    }

    /** Speed over the derived-speed baseline (or the longest span available after a gap). */
    private fun derivedSpeed(b: TracePoint): Double {
        val a = recent.firstOrNull { b.timeMs - it.timeMs <= config.derivedSpeedBaselineMs } ?: recent.lastOrNull() ?: return 0.0
        val dt = (b.timeMs - a.timeMs) / 1000.0
        if (dt <= 0.0) return 0.0
        val v = distanceM(a, b) / dt
        return if (v > config.maxPlausibleSpeedMps) 0.0 else v
    }
}
