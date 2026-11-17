package app.roadmaxxing.ui.card

import androidx.compose.runtime.Composable

/** Debug helper: returns a function that writes bytes into the app cache directory and returns the full path. */
@Composable
expect fun rememberCardPngWriter(): (String, ByteArray) -> String?
