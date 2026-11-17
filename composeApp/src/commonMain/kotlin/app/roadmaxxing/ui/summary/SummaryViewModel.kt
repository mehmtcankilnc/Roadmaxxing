package app.roadmaxxing.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.share.deviceLanguageCode
import app.roadmaxxing.shared.summary.CityCardData
import app.roadmaxxing.shared.summary.DriveCardData
import app.roadmaxxing.shared.summary.SummaryRepository
import app.roadmaxxing.ui.main.DetailTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Card data of the open detail screen. */
sealed interface CardLoad {
    data object Loading : CardLoad
    data object Unavailable : CardLoad
    data class Drive(val data: DriveCardData) : CardLoad
    data class City(val data: CityCardData) : CardLoad
}

/** Loads share-card data for the open detail target. */
class SummaryViewModel(private val repository: SummaryRepository) : ViewModel() {
    private val _state = MutableStateFlow<CardLoad>(CardLoad.Loading)
    val state: StateFlow<CardLoad> = _state.asStateFlow()

    private var loaded: DetailTarget? = null
    private var job: Job? = null

    /** Loads [target]; calling again with the same target keeps the data. */
    fun show(target: DetailTarget) {
        if (target == loaded) return
        loaded = target
        _state.value = CardLoad.Loading
        job?.cancel()
        job = viewModelScope.launch {
            val lang = deviceLanguageCode()
            _state.value = runCatching {
                when (target) {
                    is DetailTarget.Drive -> repository.driveCard(target.driveId, lang)?.let { CardLoad.Drive(it) }
                    DetailTarget.City -> repository.cityCard(lang)?.let { CardLoad.City(it) }
                }
            }.getOrNull() ?: CardLoad.Unavailable
        }
    }

    /** Forgets the data so reopening the same target (e.g. after new drives) reloads. */
    fun clear() {
        job?.cancel()
        loaded = null
    }
}
