package app.roadmaxxing.di

import app.roadmaxxing.shared.di.sharedModule
import app.roadmaxxing.ui.main.MainViewModel
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule: Module = module {
    viewModelOf(::MainViewModel)
}

/** Starts Koin with all app modules. Platforms add their own setup (e.g. Android context) via [platformSetup]. */
fun initKoin(platformSetup: KoinApplication.() -> Unit = {}): KoinApplication = startKoin {
    platformSetup()
    modules(sharedModule, appModule)
}
