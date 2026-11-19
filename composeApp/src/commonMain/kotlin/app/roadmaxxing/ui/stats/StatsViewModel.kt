package app.roadmaxxing.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.shared.stats.StatsRepository
import app.roadmaxxing.shared.stats.StatsSnapshot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** What the Stats tab shows. */
sealed interface StatsUiState {
    data object Loading : StatsUiState

    /** No counted drive yet: show an invitation instead of a wall of zeros. */
    data object Empty : StatsUiState

    data class Ready(val snapshot: StatsSnapshot) : StatsUiState
}

/** Stats tab state, derived from [StatsRepository]. */
class StatsViewModel(repository: StatsRepository) : ViewModel() {
    val state: StateFlow<StatsUiState> = repository.observe()
        .map { snapshot ->
            if (snapshot == null || snapshot.driveCount == 0) StatsUiState.Empty else StatsUiState.Ready(snapshot)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState.Loading)
}
