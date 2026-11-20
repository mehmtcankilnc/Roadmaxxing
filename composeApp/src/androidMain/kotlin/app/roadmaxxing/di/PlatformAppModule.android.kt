package app.roadmaxxing.di

import app.roadmaxxing.auth.AndroidGoogleCredentialProvider
import app.roadmaxxing.auth.CurrentActivityHolder
import app.roadmaxxing.auth.FirebaseAuthBackend
import app.roadmaxxing.auth.GoogleCredentialProvider
import app.roadmaxxing.data.AndroidDataPlatform
import app.roadmaxxing.data.DataPlatform
import app.roadmaxxing.data.dataModule
import app.roadmaxxing.notify.AndroidDriveResultPoster
import app.roadmaxxing.notify.DriveResultPoster
import app.roadmaxxing.notify.notifyModule
import app.roadmaxxing.platform.AndroidTrackingPlatform
import app.roadmaxxing.platform.TrackingPlatform
import app.roadmaxxing.shared.account.AuthBackend
import app.roadmaxxing.tracking.TrackingEngine
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformAppModule: Module = module {
    includes(notifyModule, dataModule)
    single<DriveResultPoster> { AndroidDriveResultPoster(androidContext()) }
    single { TrackingEngine(androidContext(), recorder = get(), processor = get(), status = get(), notifier = get()) }
    single<TrackingPlatform> { AndroidTrackingPlatform(androidContext(), engine = get()) }
    single<DataPlatform> { AndroidDataPlatform(androidContext(), engine = get()) }
    // Overrides NoopAuthBackend from sharedModule (appModule is loaded after it). Unavailable without Firebase config.
    single(createdAtStart = true) { CurrentActivityHolder(androidContext() as android.app.Application) }
    single<GoogleCredentialProvider> { AndroidGoogleCredentialProvider(androidContext(), get()) }
    single<AuthBackend> { FirebaseAuthBackend(google = get()) }
}
