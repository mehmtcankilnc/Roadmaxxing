package app.roadmaxxing.ui.celebration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.share.deviceLanguageCode
import app.roadmaxxing.shared.milestones.MilestoneRepository
import app.roadmaxxing.shared.summary.SummaryRepository
import app.roadmaxxing.ui.card.MilestoneCardData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the celebration to show: the pending one from [MilestoneRepository] together with the city card data its
 * card needs. [card] is null while nothing is pending or the card data is not ready, so the overlay never shows
 * half a screen. The shell decides WHEN it may appear; this class only prepares WHAT.
 */
class CelebrationViewModel(
    private val milestones: MilestoneRepository,
    private val summary: SummaryRepository,
) : ViewModel() {

    private val _card = MutableStateFlow<MilestoneCardData?>(null)
    val card: StateFlow<MilestoneCardData?> = _card.asStateFlow()

    init {
        viewModelScope.launch {
            milestones.pending.collectLatest { celebration ->
                if (celebration == null) {
                    _card.value = null
                    return@collectLatest
                }
                val city = try {
                    withContext(Dispatchers.Default) { summary.cityCard(deviceLanguageCode()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                // No city data: stay silent; the celebration stays pending for the next attempt.
                _card.value = city?.let { MilestoneCardData(it, celebration) }
            }
        }
    }

    /** Closes the celebration for good: it is marked as celebrated and never returns. */
    fun dismiss() {
        val shown = _card.value ?: return
        _card.value = null
        milestones.markCelebrated(shown.celebration)
    }
}
