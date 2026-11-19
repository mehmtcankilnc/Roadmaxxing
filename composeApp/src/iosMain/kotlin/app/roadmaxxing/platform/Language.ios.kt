package app.roadmaxxing.platform

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

actual val supportsPerAppLanguage: Boolean = true

actual fun openLanguageSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
}
