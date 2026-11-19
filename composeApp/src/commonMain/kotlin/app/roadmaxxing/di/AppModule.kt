package app.roadmaxxing.di

import app.roadmaxxing.shared.di.sharedModule
import app.roadmaxxing.ui.explore.ExploreViewModel
import app.roadmaxxing.ui.main.MainViewModel
import app.roadmaxxing.ui.onboarding.OnboardingViewModel
import app.roadmaxxing.ui.privacy.PrivacyZonesViewModel
import app.roadmaxxing.ui.settings.SettingsViewModel
import app.roadmaxxing.ui.stats.StatsViewModel
import app.roadmaxxing.ui.summary.SummaryViewModel
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** Platform bindings of the app layer (TrackingPlatform and the platform tracking engine). */
internal expect val platformAppModule: Module

val appModule: Module = module {
    includes(platformAppModule)
    viewModelOf(::MainViewModel)
    viewModelOf(::ExploreViewModel)
    viewModelOf(::SummaryViewModel)
    viewModelOf(::PrivacyZonesViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::StatsViewModel)
    viewModel { OnboardingViewModel(repository = get(), introRoadsProvider = get()) }
}

/** Starts Koin with all app modules. Platforms add their own setup (e.g. Android context) via [platformSetup]. */
fun initKoin(platformSetup: KoinApplication.() -> Unit = {}): KoinApplication = startKoin {
    platformSetup()
    modules(sharedModule, appModule)
}
