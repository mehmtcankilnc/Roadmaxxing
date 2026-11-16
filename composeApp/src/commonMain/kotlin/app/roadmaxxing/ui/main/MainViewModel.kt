package app.roadmaxxing.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.shared.data.SetupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val setupRepository: SetupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        onIntent(MainIntent.Refresh)
    }

    fun onIntent(intent: MainIntent) {
        when (intent) {
            MainIntent.Refresh -> refresh()
        }
    }

    private fun refresh() {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val status = setupRepository.checkAndRecord()
            _state.update { it.copy(isLoading = false, setupStatus = status) }
        }
    }
}
