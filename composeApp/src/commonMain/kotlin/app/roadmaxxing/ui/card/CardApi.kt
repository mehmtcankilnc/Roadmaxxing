package app.roadmaxxing.ui.card

import androidx.compose.runtime.Composable
import app.roadmaxxing.shared.summary.CityCardData
import app.roadmaxxing.shared.summary.DriveCardData

/** Share-card formats. Pixel sizes are the exact PNG output sizes. */
enum class CardFormat(val widthPx: Int, val heightPx: Int) {
    Story(1080, 1920),
    Post(1080, 1350),
}

/** Renders cards off screen to PNG bytes of exactly `widthPx x heightPx`. */
interface CardRenderer {
    suspend fun renderDriveCard(data: DriveCardData, format: CardFormat, hideSpeed: Boolean): ByteArray
    suspend fun renderCityCard(data: CityCardData, format: CardFormat): ByteArray
}

/** Platform renderer. Android needs a window (it hosts an off-screen ComposeView); iOS uses ImageComposeScene. */
@Composable
expect fun rememberCardRenderer(): CardRenderer
