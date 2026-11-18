package app.roadmaxxing.notify

import app.roadmaxxing.shared.onboarding.OnboardingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-over point for "open the summary of this drive" requests that start outside Compose (a tapped
 * end-of-drive notification). Platform code calls [open]; AppShell observes [driveId], opens the summary and
 * calls [consume].
 */
class PendingDriveNavigation(private val isOnboardingCompleted: () -> Boolean) {
    constructor(onboarding: OnboardingRepository) : this({ onboarding.isCompleted.value })

    private val pending = MutableStateFlow<Long?>(null)

    /** The drive whose summary should open next, or null. */
    val driveId: StateFlow<Long?> = pending.asStateFlow()

    /** Requests the summary of [id]. Ignored until onboarding is completed. */
    fun open(id: Long) {
        if (isOnboardingCompleted()) pending.value = id
    }

    /** Marks the request as handled (only if it is still [id], so a newer tap is not lost). */
    fun consume(id: Long) {
        pending.compareAndSet(id, null)
    }
}
