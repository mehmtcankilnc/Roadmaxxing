package app.roadmaxxing.ui.card

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.roadmaxxing.shared.summary.CityCardData
import app.roadmaxxing.shared.summary.DriveCardData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
actual fun rememberCardRenderer(): CardRenderer {
    val view = LocalView.current
    return remember(view) { AndroidCardRenderer(view) }
}

/**
 * Renders a card in an off-screen [ComposeView] attached to the window's decor view, scaled to 0.001 so it is
 * invisible but still drawn. The composable records itself into a GraphicsLayer at full pixel size, which is then
 * read back as a bitmap and PNG-encoded with Bitmap.compress.
 */
private class AndroidCardRenderer(private val anchor: View) : CardRenderer {
    override suspend fun renderDriveCard(data: DriveCardData, format: CardFormat, hideSpeed: Boolean): ByteArray =
        render(format) { DriveCard(data, format, hideSpeed, Modifier.fillMaxSize()) }

    override suspend fun renderCityCard(data: CityCardData, format: CardFormat): ByteArray =
        render(format) { CityCard(data, format, Modifier.fillMaxSize()) }

    override suspend fun renderMilestoneCard(data: MilestoneCardData, format: CardFormat): ByteArray =
        render(format) { MilestoneCard(data, format, Modifier.fillMaxSize()) }

    private suspend fun render(format: CardFormat, content: @Composable () -> Unit): ByteArray =
        withContext(Dispatchers.Main) {
            val host = anchor.rootView as ViewGroup
            val done = CompletableDeferred<ByteArray>()
            val composeView = ComposeView(anchor.context).apply {
                layoutParams = FrameLayout.LayoutParams(format.widthPx, format.heightPx)
                pivotX = 0f
                pivotY = 0f
                scaleX = 0.001f
                scaleY = 0.001f
                setContent { Capture(format, done, content) }
            }
            host.addView(composeView)
            try {
                done.await()
            } finally {
                host.removeView(composeView)
            }
        }
}

@Composable
private fun Capture(format: CardFormat, done: CompletableDeferred<ByteArray>, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
        Box(
            Modifier
                .size(format.widthPx.dp, format.heightPx.dp)
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) { content() }
    }
    LaunchedEffect(Unit) {
        // Fonts load asynchronously from resources; give them time, then let a few frames draw.
        delay(600)
        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
        try {
            val bitmap = layer.toImageBitmap().asAndroidBitmap()
            check(bitmap.width == format.widthPx && bitmap.height == format.heightPx) {
                "Card bitmap is ${bitmap.width}x${bitmap.height}, expected ${format.widthPx}x${format.heightPx}"
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            done.complete(out.toByteArray())
        } catch (t: Throwable) {
            done.completeExceptionally(t)
        }
    }
}
