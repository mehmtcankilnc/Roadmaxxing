package app.roadmaxxing.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

private val RoadmaxxingColorScheme = darkColorScheme(
    primary = RoadmaxxingPalette.Primary,
    onPrimary = RoadmaxxingPalette.OnPrimary,
    background = RoadmaxxingPalette.Background,
    onBackground = RoadmaxxingPalette.TextPrimary,
    surface = RoadmaxxingPalette.Surface,
    onSurface = RoadmaxxingPalette.TextPrimary,
    surfaceVariant = RoadmaxxingPalette.SurfaceRaised,
    onSurfaceVariant = RoadmaxxingPalette.TextSecondary,
    surfaceContainer = RoadmaxxingPalette.Surface,
    surfaceContainerHigh = RoadmaxxingPalette.SurfaceRaised,
    outline = RoadmaxxingPalette.RoadUnexplored,
    outlineVariant = RoadmaxxingPalette.RoadUnexplored,
    error = RoadmaxxingPalette.Danger,
    onError = RoadmaxxingPalette.Background,
)

/** Dark-only app theme. There is intentionally no light variant. */
@Composable
fun RoadmaxxingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RoadmaxxingColorScheme,
        typography = RoadmaxxingTypography,
        content = content,
    )
}

object RoadmaxxingTheme {
    /** Tokens without a Material 3 slot (textSecondary, surfaceRaised, roadUnexplored). */
    val extraColors: RoadmaxxingExtraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRoadmaxxingExtraColors.current
}
