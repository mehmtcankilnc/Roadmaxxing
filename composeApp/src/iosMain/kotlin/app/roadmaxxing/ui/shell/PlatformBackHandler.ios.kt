package app.roadmaxxing.ui.shell

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS has no system back button; tab navigation goes through the tab bar only.
}
