package app.roadmaxxing.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.koin.compose.koinInject

/**
 * Live permission states. Re-read on composition, after every request result and whenever the app returns to the
 * foreground (for example from the system settings page).
 */
@Composable
fun rememberPermissionStates(): State<Map<AppPermission, PermissionState>> {
    val platform = koinInject<TrackingPlatform>()
    val states = remember { mutableStateOf(platform.permissionStates()) }
    val refresh = { states.value = platform.permissionStates() }
    val currentRefresh = rememberUpdatedState(refresh)
    // Refresh after a request result as well: the requester calls this through the observer below.
    val observer = remember { PermissionRefreshObserver }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val listener: () -> Unit = { currentRefresh.value() }
        observer.listeners += listener
        onDispose { observer.listeners -= listener }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    return states
}

/** Lets requesters tell every live [rememberPermissionStates] that a request finished. */
internal object PermissionRefreshObserver {
    val listeners = mutableListOf<() -> Unit>()
    fun notifyChanged() = listeners.toList().forEach { it() }
}
