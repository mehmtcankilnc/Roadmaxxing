package app.roadmaxxing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadmaxxing.shared.onboarding.OnboardingRepository
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.onboarding.OnboardingFlow
import app.roadmaxxing.ui.shell.AppShell
import org.koin.compose.koinInject

/** Root composable shared by Android and iOS. The platform must start Koin before this runs. */
@Composable
fun App() {
    RoadmaxxingTheme {
        val onboarding = koinInject<OnboardingRepository>()
        val completed by onboarding.isCompleted.collectAsStateWithLifecycle()
        if (completed) AppShell() else OnboardingFlow()
    }
}
