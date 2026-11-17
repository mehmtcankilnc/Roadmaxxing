package app.roadmaxxing.share

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
actual fun rememberCardSharer(): CardSharer {
    val context = LocalContext.current
    // Only used on API 26-28; completes with the user's answer.
    val holder = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        holder[0]?.complete(granted)
    }
    return remember(context) {
        AndroidCardSharer(context) {
            val d = CompletableDeferred<Boolean>()
            holder[0] = d
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            d.await()
        }
    }
}

actual fun deviceLanguageCode(): String = Locale.getDefault().language

private class AndroidCardSharer(
    private val context: Context,
    private val requestLegacyStoragePermission: suspend () -> Boolean,
) : CardSharer {

    override fun share(png: ByteArray, fileName: String) {
        val dir = File(context.cacheDir, "share_cards").apply { mkdirs() }
        val file = File(dir, fileName).apply { writeBytes(png) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null)
        if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    override suspend fun saveToGallery(png: ByteArray, fileName: String): Result<Unit> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED || requestLegacyStoragePermission()
            if (!granted) throw SaveFailure.PermissionDenied()
        }
        withContext(Dispatchers.IO) { writeToMediaStore(png, fileName) }
    }

    private fun writeToMediaStore(png: ByteArray, fileName: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Roadmaxxing")
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { it.write(png) } ?: error("No output stream")
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Roadmaxxing")
            dir.mkdirs()
            val file = File(dir, fileName).apply { writeBytes(png) }
            @Suppress("DEPRECATION")
            values.put(MediaStore.Images.Media.DATA, file.absolutePath)
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        }
    }
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
