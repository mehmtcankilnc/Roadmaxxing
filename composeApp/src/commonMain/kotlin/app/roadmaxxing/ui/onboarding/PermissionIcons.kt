package app.roadmaxxing.ui.onboarding

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import app.roadmaxxing.platform.AppPermission

// Outline glyphs drawn here because no icon pack is a dependency. The Icon composable applies the tint.

private fun outlineIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
        .build()

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
    arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
}

/** Map pin: location while the app is in use. */
private val PinIcon = outlineIcon("pin") {
    moveTo(12f, 21f)
    curveTo(8f, 16f, 5f, 13f, 5f, 9f)
    arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 19f, y1 = 9f)
    curveTo(19f, 13f, 16f, 16f, 12f, 21f)
    close()
    circle(12f, 9f, 2.5f)
}

/** Target rings: location that keeps working in the background. */
private val TargetIcon = outlineIcon("target") {
    circle(12f, 12f, 9f)
    circle(12f, 12f, 4.5f)
    moveTo(12f, 2f); lineTo(12f, 5f)
    moveTo(12f, 19f); lineTo(12f, 22f)
    moveTo(2f, 12f); lineTo(5f, 12f)
    moveTo(19f, 12f); lineTo(22f, 12f)
}

/** Pulse line: motion and activity. */
private val MotionIcon = outlineIcon("motion") {
    moveTo(2f, 12f); lineTo(6f, 12f); lineTo(9f, 5f); lineTo(13f, 19f); lineTo(16f, 12f); lineTo(22f, 12f)
}

private val BellIcon = outlineIcon("bell") {
    moveTo(6f, 16f)
    lineTo(6f, 10f)
    arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 18f, y1 = 10f)
    lineTo(18f, 16f)
    moveTo(4f, 16f); lineTo(20f, 16f)
    moveTo(10f, 19.5f); lineTo(14f, 19.5f)
}

internal fun AppPermission.icon(): ImageVector = when (this) {
    AppPermission.Location -> PinIcon
    AppPermission.BackgroundLocation -> TargetIcon
    AppPermission.ActivityRecognition -> MotionIcon
    AppPermission.Notifications -> BellIcon
}
