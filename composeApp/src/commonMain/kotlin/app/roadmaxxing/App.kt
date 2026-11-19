package app.roadmaxxing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roadmaxxing.platform.deviceRegionCode
import app.roadmaxxing.shared.onboarding.OnboardingRepository
import app.roadmaxxing.shared.settings.SettingsRepository
import app.roadmaxxing.theme.RoadmaxxingTheme
import app.roadmaxxing.ui.onboarding.OnboardingFlow
import app.roadmaxxing.ui.shell.AppShell
import org.koin.compose.koinInject

/** Root composable shared by Android and iOS. The platform must start Koin before this runs. */
@Composable
fun App() {
    val settings = koinInject<SettingsRepository>()
    // Region based default unit (miles for US/GB); a no-op once the user chose a unit.
    LaunchedEffect(Unit) { settings.initDefaultUnit(deviceRegionCode()) }
    RoadmaxxingTheme {
        val onboarding = koinInject<OnboardingRepository>()
        val completed by onboarding.isCompleted.collectAsStateWithLifecycle()
        if (completed) AppShell() else OnboardingFlow()
    }
}
