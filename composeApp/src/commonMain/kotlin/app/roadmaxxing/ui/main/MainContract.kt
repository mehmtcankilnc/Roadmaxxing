package app.roadmaxxing.ui.main

import app.roadmaxxing.shared.domain.SetupStatus

/** Immutable screen state (MVI model). */
data class MainUiState(
    val isLoading: Boolean = true,
    val setupStatus: SetupStatus? = null,
)

/** Intents handled by [MainViewModel]. */
sealed interface MainIntent {
    data object Refresh : MainIntent
}
