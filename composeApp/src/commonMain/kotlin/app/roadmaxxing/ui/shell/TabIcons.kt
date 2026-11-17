package app.roadmaxxing.ui.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import app.roadmaxxing.ui.main.AppTab

// Simple outline glyphs drawn here because the icon packs are not a dependency. The icon tint is applied by
// the Icon composable, so the stroke colour below is irrelevant.

private fun outlineIcon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
        .build()

private val MapIcon = outlineIcon("map") {
    moveTo(3f, 6f); lineTo(9f, 4f); lineTo(15f, 6f); lineTo(21f, 4f); lineTo(21f, 18f); lineTo(15f, 20f)
    lineTo(9f, 18f); lineTo(3f, 20f); close()
    moveTo(9f, 4f); lineTo(9f, 18f)
    moveTo(15f, 6f); lineTo(15f, 20f)
}

private val DrivesIcon = outlineIcon("drives") {
    moveTo(8f, 3f); lineTo(5f, 21f)
    moveTo(16f, 3f); lineTo(19f, 21f)
    moveTo(12f, 4f); lineTo(12f, 7f)
    moveTo(12f, 10.5f); lineTo(12f, 13.5f)
    moveTo(12f, 17f); lineTo(12f, 20f)
}

private val StatsIcon = outlineIcon("stats") {
    moveTo(5f, 20f); lineTo(5f, 12f)
    moveTo(12f, 20f); lineTo(12f, 5f)
    moveTo(19f, 20f); lineTo(19f, 9f)
}

private val ProfileIcon = outlineIcon("profile") {
    moveTo(8f, 8f)
    arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 16f, y1 = 8f)
    arcTo(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 8f, y1 = 8f)
    moveTo(4f, 20f)
    curveTo(4f, 16f, 8f, 14f, 12f, 14f)
    curveTo(16f, 14f, 20f, 16f, 20f, 20f)
}

internal fun AppTab.icon(): ImageVector = when (this) {
    AppTab.Map -> MapIcon
    AppTab.Drives -> DrivesIcon
    AppTab.Stats -> StatsIcon
    AppTab.Profile -> ProfileIcon
}
