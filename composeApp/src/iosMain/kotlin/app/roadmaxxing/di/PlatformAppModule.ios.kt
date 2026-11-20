package app.roadmaxxing.di

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import app.roadmaxxing.auth.FirebaseAuthBackend
import app.roadmaxxing.auth.GoogleCredentialProvider
import app.roadmaxxing.auth.IosGoogleCredentialProvider
import app.roadmaxxing.data.DataPlatform
import app.roadmaxxing.data.IosDataPlatform
import app.roadmaxxing.data.dataModule
import app.roadmaxxing.notify.DriveResultPoster
import app.roadmaxxing.notify.IosDriveResultPoster
import app.roadmaxxing.notify.notifyModule
import app.roadmaxxing.platform.IosTrackingPlatform
import app.roadmaxxing.platform.TrackingPlatform
import app.roadmaxxing.shared.account.AuthBackend
import app.roadmaxxing.tracking.IosTrackingEngine
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformAppModule: Module = module {
    includes(notifyModule, dataModule)
    single<DriveResultPoster> { IosDriveResultPoster(navigation = get()) }
    // Created on first use. IosTrackingEngine must be constructed on the main thread (CLLocationManager);
    // startTrackingOnLaunch() does that explicitly at launch.
    single { IosTrackingEngine(recorder = get(), processor = get(), status = get(), notifier = get()) }
    single<TrackingPlatform> { IosTrackingPlatform(get()) }
    single<DataPlatform> { IosDataPlatform(engine = get()) }
    // Overrides NoopAuthBackend from sharedModule (appModule is loaded after it). Unavailable without Firebase config.
    single<GoogleCredentialProvider> { IosGoogleCredentialProvider() }
    single<AuthBackend> { FirebaseAuthBackend(google = get()) }
}
