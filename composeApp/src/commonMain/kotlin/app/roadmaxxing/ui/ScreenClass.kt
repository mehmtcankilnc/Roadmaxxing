package app.roadmaxxing.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/** Width-based screen class used to pick a layout. Never branch on a specific device. */
enum class ScreenClass { Compact, Medium, Expanded }

@Composable
fun currentScreenClass(): ScreenClass {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> ScreenClass.Expanded
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> ScreenClass.Medium
        else -> ScreenClass.Compact
    }
}
