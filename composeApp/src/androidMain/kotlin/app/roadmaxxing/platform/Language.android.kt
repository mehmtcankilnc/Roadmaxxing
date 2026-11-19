package app.roadmaxxing.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.koin.mp.KoinPlatform

actual val supportsPerAppLanguage: Boolean = Build.VERSION.SDK_INT >= 33

actual fun openLanguageSettings() {
    if (!supportsPerAppLanguage) return
    val context = KoinPlatform.getKoin().get<Context>()
    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
