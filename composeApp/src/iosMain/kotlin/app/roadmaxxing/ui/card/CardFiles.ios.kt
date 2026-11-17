package app.roadmaxxing.ui.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

/** NOT VERIFIED on CI yet. Debug-only PNG writer into the Caches directory. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberCardPngWriter(): (String, ByteArray) -> String? = remember {
    { name, bytes ->
        val dir = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        if (dir == null || bytes.isEmpty()) {
            null
        } else {
            val path = "$dir/$name"
            val ok = bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()).writeToFile(path, true)
            }
            if (ok) path else null
        }
    }
}
