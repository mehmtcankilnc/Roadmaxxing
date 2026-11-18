package app.roadmaxxing.platform

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Android has no single "reduce motion" flag; a zero animator duration scale (Developer options / Accessibility) is it. */
@Composable
actual fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduce by remember { mutableStateOf(context.animationsDisabled()) }
    // The user may change the setting while the app is in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reduce = context.animationsDisabled() }
    return reduce
}

private fun Context.animationsDisabled(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
