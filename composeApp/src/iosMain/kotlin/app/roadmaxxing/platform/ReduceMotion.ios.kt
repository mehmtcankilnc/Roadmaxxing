package app.roadmaxxing.platform

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
actual fun rememberReduceMotion(): Boolean {
    var reduce by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    // Re-read when the app returns from Settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reduce = UIAccessibilityIsReduceMotionEnabled() }
    return reduce
}
