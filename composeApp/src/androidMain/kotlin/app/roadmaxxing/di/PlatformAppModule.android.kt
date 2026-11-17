package app.roadmaxxing.di

import app.roadmaxxing.platform.AndroidTrackingPlatform
import app.roadmaxxing.platform.TrackingPlatform
import app.roadmaxxing.tracking.TrackingEngine
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformAppModule: Module = module {
    single { TrackingEngine(androidContext(), recorder = get(), processor = get(), status = get()) }
    single<TrackingPlatform> { AndroidTrackingPlatform(androidContext(), engine = get()) }
}
