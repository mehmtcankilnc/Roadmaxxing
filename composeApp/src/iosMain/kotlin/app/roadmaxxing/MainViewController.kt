package app.roadmaxxing

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point called from Swift as `MainViewControllerKt.MainViewController()`. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    startTrackingOnLaunch()
    return ComposeUIViewController { App() }
}
