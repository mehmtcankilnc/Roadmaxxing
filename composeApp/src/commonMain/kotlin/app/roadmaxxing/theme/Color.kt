package app.roadmaxxing.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Design tokens from docs/DESIGN.md. The app has a dark theme only. */
internal object RoadmaxxingPalette {
    val Primary = Color(0xFFC8FF3D)
    val OnPrimary = Color(0xFF1A2600)
    val Background = Color(0xFF0B0B0D)
    val Surface = Color(0xFF16171B)
    val SurfaceRaised = Color(0xFF1F2026)
    val TextPrimary = Color(0xFFF2F0EA)
    val TextSecondary = Color(0xFF8A8D94)
    val RoadUnexplored = Color(0xFF2C2F36)
    val Danger = Color(0xFFFF5A5F)
}

/** Tokens that have no Material 3 slot. */
@Immutable
data class RoadmaxxingExtraColors(
    val surfaceRaised: Color,
    val textSecondary: Color,
    val roadUnexplored: Color,
)

internal val LocalRoadmaxxingExtraColors = staticCompositionLocalOf {
    RoadmaxxingExtraColors(
        surfaceRaised = RoadmaxxingPalette.SurfaceRaised,
        textSecondary = RoadmaxxingPalette.TextSecondary,
        roadUnexplored = RoadmaxxingPalette.RoadUnexplored,
    )
}
