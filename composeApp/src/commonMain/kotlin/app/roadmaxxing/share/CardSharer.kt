package app.roadmaxxing.share

import androidx.compose.runtime.Composable

/** Hands a rendered card image to the user: system share sheet or the photo gallery. */
interface CardSharer {
    /** Opens the system share sheet with the PNG. Fire and forget. */
    fun share(png: ByteArray, fileName: String)

    /** Saves the PNG to the gallery. Fails with [SaveFailure] subtypes or another exception. */
    suspend fun saveToGallery(png: ByteArray, fileName: String): Result<Unit>
}

/** Why saving to the gallery failed in a way the user can act on. */
sealed class SaveFailure(message: String) : Exception(message) {
    class PermissionDenied : SaveFailure("Storage permission denied")
}

/** Platform sharer; needs UI context (activity / view controller), so it is created inside composition. */
@Composable
expect fun rememberCardSharer(): CardSharer

/** Two-letter language code of the device (e.g. "tr", "en") for card texts. */
expect fun deviceLanguageCode(): String
