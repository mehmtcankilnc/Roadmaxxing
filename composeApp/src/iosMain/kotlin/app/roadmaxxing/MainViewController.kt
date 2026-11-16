package app.roadmaxxing

import androidx.compose.ui.window.ComposeUIViewController
import app.roadmaxxing.di.initKoin
import org.koin.mp.KoinPlatform
import platform.UIKit.UIViewController

/** Entry point called from Swift as `MainViewControllerKt.MainViewController()`. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    if (KoinPlatform.getKoinOrNull() == null) {
        initKoin()
    }
    return ComposeUIViewController { App() }
}
