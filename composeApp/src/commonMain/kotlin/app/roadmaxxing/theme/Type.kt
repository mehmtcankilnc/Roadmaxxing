package app.roadmaxxing.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.roadmaxxing.resources.Res
import app.roadmaxxing.resources.inter_bold
import app.roadmaxxing.resources.inter_medium
import app.roadmaxxing.resources.inter_regular
import app.roadmaxxing.resources.inter_semibold
import org.jetbrains.compose.resources.Font

/** Inter (SIL OFL 1.1, see files/licenses/Inter-OFL.txt), static weights 400/500/600/700. */
@Composable
internal fun interFontFamily(): FontFamily {
    val regular = Font(Res.font.inter_regular, FontWeight.Normal)
    val medium = Font(Res.font.inter_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.inter_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.inter_bold, FontWeight.Bold)
    return remember(regular, medium, semiBold, bold) { FontFamily(regular, medium, semiBold, bold) }
}

// Type scale from docs/DESIGN.md: title 34/700, subtitle 22/600, body 16/400, label 13/500.
// Tabular digits ("tnum") keep percentages and kilometre counters from jumping.
private const val TABULAR = "tnum"

private fun scale(family: FontFamily) = listOf(
    TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, fontFeatureSettings = TABULAR),
    TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, fontFeatureSettings = TABULAR),
    TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, fontFeatureSettings = TABULAR),
    TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TABULAR),
)

/** Composable getter: font resources can only be loaded from composition. */
internal val RoadmaxxingTypography: Typography
    @Composable
    get() {
        val family = interFontFamily()
        return remember(family) {
            val (title, subtitle, body, label) = scale(family)
            Typography().copy(
                displaySmall = title,
                headlineLarge = title,
                headlineSmall = subtitle,
                titleLarge = subtitle,
                bodyLarge = body,
                bodyMedium = body,
                labelLarge = label,
                labelMedium = label,
            )
        }
    }
