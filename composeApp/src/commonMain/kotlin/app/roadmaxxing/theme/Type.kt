package app.roadmaxxing.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Type scale from docs/DESIGN.md: title 34/700, subtitle 22/600, body 16/400, label 13/500.
// Inter is the target typeface; until its font files are added, the system font is used.
private val AppFontFamily = FontFamily.Default

private val Title = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp)
private val Subtitle = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
private val Body = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
private val Label = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)

internal val RoadmaxxingTypography: Typography = Typography().copy(
    displaySmall = Title,
    headlineLarge = Title,
    headlineSmall = Subtitle,
    titleLarge = Subtitle,
    bodyLarge = Body,
    bodyMedium = Body,
    labelLarge = Label,
    labelMedium = Label,
)
