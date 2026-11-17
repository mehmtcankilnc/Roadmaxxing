package app.roadmaxxing.di

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import app.roadmaxxing.platform.IosTrackingPlatform
import app.roadmaxxing.platform.TrackingPlatform
import app.roadmaxxing.tracking.IosTrackingEngine
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformAppModule: Module = module {
    // Created on first use. IosTrackingEngine must be constructed on the main thread (CLLocationManager);
    // startTrackingOnLaunch() does that explicitly at launch.
    single { IosTrackingEngine(recorder = get(), processor = get(), status = get()) }
    single<TrackingPlatform> { IosTrackingPlatform(get()) }
}
