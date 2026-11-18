package app.roadmaxxing.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roadmaxxing.platform.AppPermission
import app.roadmaxxing.shared.onboarding.IntroRoads
import app.roadmaxxing.shared.onboarding.IntroRoadsProvider
import app.roadmaxxing.shared.onboarding.OnboardingRepository
import app.roadmaxxing.ui.legal.LegalDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { Intro1, Intro2, Consent, Permissions, Done }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Intro1,
    val safeAccepted: Boolean = false,
    val termsAccepted: Boolean = false,
    /** Legal text open as a full-screen overlay, if any. */
    val legal: LegalDocument? = null,
    /** Permission cards the user postponed, in order. Doubles as the back history of the permission step. */
    val skippedPermissions: List<AppPermission> = emptyList(),
    /** Null until the intro road data has loaded; the animation shows gray roads (or nothing) meanwhile. */
    val introRoads: IntroRoads? = null,
) {
    val canContinueFromConsent: Boolean get() = safeAccepted && termsAccepted
}

sealed interface OnboardingIntent {
    data object Next : OnboardingIntent
    data object Back : OnboardingIntent
    data class SetSafeAccepted(val value: Boolean) : OnboardingIntent
    data class SetTermsAccepted(val value: Boolean) : OnboardingIntent
    data class OpenLegal(val document: LegalDocument) : OnboardingIntent
    data object CloseLegal : OnboardingIntent
    data class SkipPermission(val permission: AppPermission) : OnboardingIntent

    /** No permission card is left to show: move on to the done screen. */
    data object PermissionsExhausted : OnboardingIntent
    data object Finish : OnboardingIntent
}

class OnboardingViewModel(
    private val repository: OnboardingRepository,
    introRoadsProvider: IntroRoadsProvider,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val roads = introRoadsProvider.load()
                _state.update { it.copy(introRoads = roads) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The animation is decorative; without road data it stays on the gray placeholder.
            }
        }
    }

    /** True when system back should be handled by onboarding (everywhere except the first screen). */
    fun handlesBack(state: OnboardingUiState): Boolean = state.legal != null || state.step != OnboardingStep.Intro1

    fun onIntent(intent: OnboardingIntent) {
        when (intent) {
            OnboardingIntent.Next -> next()
            OnboardingIntent.Back -> back()
            is OnboardingIntent.SetSafeAccepted -> _state.update { it.copy(safeAccepted = intent.value) }
            is OnboardingIntent.SetTermsAccepted -> _state.update { it.copy(termsAccepted = intent.value) }
            is OnboardingIntent.OpenLegal -> _state.update { it.copy(legal = intent.document) }
            OnboardingIntent.CloseLegal -> _state.update { it.copy(legal = null) }
            is OnboardingIntent.SkipPermission -> _state.update {
                if (intent.permission in it.skippedPermissions) it else it.copy(skippedPermissions = it.skippedPermissions + intent.permission)
            }
            OnboardingIntent.PermissionsExhausted -> _state.update {
                if (it.step == OnboardingStep.Permissions) it.copy(step = OnboardingStep.Done) else it
            }
            OnboardingIntent.Finish -> repository.markCompleted()
        }
    }

    private fun next() {
        val s = _state.value
        when (s.step) {
            OnboardingStep.Intro1 -> _state.update { it.copy(step = OnboardingStep.Intro2) }
            OnboardingStep.Intro2 -> _state.update { it.copy(step = OnboardingStep.Consent) }
            OnboardingStep.Consent -> {
                if (!s.canContinueFromConsent) return
                repository.recordConsent(acceptedAtEpochMillis = clock())
                _state.update { it.copy(step = OnboardingStep.Permissions) }
            }
            OnboardingStep.Permissions -> _state.update { it.copy(step = OnboardingStep.Done) }
            OnboardingStep.Done -> Unit
        }
    }

    private fun back() {
        _state.update { s ->
            when {
                s.legal != null -> s.copy(legal = null)
                s.step == OnboardingStep.Intro1 -> s
                s.step == OnboardingStep.Intro2 -> s.copy(step = OnboardingStep.Intro1)
                s.step == OnboardingStep.Consent -> s.copy(step = OnboardingStep.Intro2)
                // Step back through the postponed cards first, then to the consent screen.
                s.step == OnboardingStep.Permissions || s.step == OnboardingStep.Done ->
                    if (s.skippedPermissions.isNotEmpty()) {
                        s.copy(step = OnboardingStep.Permissions, skippedPermissions = s.skippedPermissions.dropLast(1))
                    } else {
                        s.copy(step = OnboardingStep.Consent)
                    }
                else -> s
            }
        }
    }
}
