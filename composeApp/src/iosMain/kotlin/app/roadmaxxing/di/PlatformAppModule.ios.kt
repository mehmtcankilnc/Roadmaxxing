package app.roadmaxxing.di

import app.roadmaxxing.platform.IosTrackingPlatform
import app.roadmaxxing.platform.TrackingPlatform
import org.koin.core.module.Module
import org.koin.dsl.module

// NOT VERIFIED: iosMain cannot be compiled on Windows.
internal actual val platformAppModule: Module = module {
    single<TrackingPlatform> { IosTrackingPlatform() }
}
