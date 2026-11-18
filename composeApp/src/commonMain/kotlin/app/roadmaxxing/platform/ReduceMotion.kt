package app.roadmaxxing.platform

import androidx.compose.runtime.Composable

/**
 * True when the user asked the system for reduced motion (Android: animator duration scale 0; iOS: Reduce
 * Motion). Animations should then be skipped or shortened to an instant state change.
 */
@Composable
expect fun rememberReduceMotion(): Boolean
