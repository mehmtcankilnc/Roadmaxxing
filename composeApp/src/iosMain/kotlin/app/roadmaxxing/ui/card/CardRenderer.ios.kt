package app.roadmaxxing.ui.card

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import app.roadmaxxing.shared.summary.CityCardData
import app.roadmaxxing.shared.summary.DriveCardData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat

@Composable
actual fun rememberCardRenderer(): CardRenderer = remember { IosCardRenderer() }

/**
 * NOT VERIFIED on CI yet (no Mac available locally). Draws the card in an off-screen skiko [ImageComposeScene]
 * at density 1 and encodes the Skia image to PNG; no UIKit and no Swift involved.
 * Risks: ImageComposeScene is experimental API in Compose Multiplatform; fonts load asynchronously, hence the
 * delay before the final frame is taken.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class IosCardRenderer : CardRenderer {
    override suspend fun renderDriveCard(data: DriveCardData, format: CardFormat, hideSpeed: Boolean): ByteArray =
        render(format) { DriveCard(data, format, hideSpeed, Modifier.fillMaxSize()) }

    override suspend fun renderCityCard(data: CityCardData, format: CardFormat): ByteArray =
        render(format) { CityCard(data, format, Modifier.fillMaxSize()) }

    private suspend fun render(format: CardFormat, content: @Composable () -> Unit): ByteArray =
        withContext(Dispatchers.Main) {
            val scene = ImageComposeScene(format.widthPx, format.heightPx, Density(1f), content = content)
            try {
                var now = 0L
                scene.render(now)
                repeat(6) {
                    delay(100)
                    now += 100_000_000L
                    scene.render(now)
                }
                now += 16_000_000L
                val image = scene.render(now)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
                data.bytes
            } finally {
                scene.close()
            }
        }
}

