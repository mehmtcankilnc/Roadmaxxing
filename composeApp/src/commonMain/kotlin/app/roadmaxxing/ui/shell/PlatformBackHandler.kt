package app.roadmaxxing.ui.shell

import androidx.compose.runtime.Composable

/**
 * Intercepts the system back action while [enabled]. Android back button / gesture only; iOS has none, so
 * its actual does nothing (kept out of common code because ui-backhandler is not a project dependency).
 */
@Composable
internal expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
