package app.roadmaxxing.ui.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
actual fun rememberCardPngWriter(): (String, ByteArray) -> String? {
    val dir = LocalContext.current.cacheDir
    return remember(dir) {
        { name, bytes -> File(dir, name).also { it.writeBytes(bytes) }.absolutePath }
    }
}
