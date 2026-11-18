package app.roadmaxxing.di

import app.roadmaxxing.notify.AndroidDriveResultPoster
import app.roadmaxxing.notify.DriveResultPoster
import app.roadmaxxing.notify.notifyModule
import app.roadmaxxing.platform.AndroidTrackingPlatform
import app.roadmaxxing.platform.TrackingPlatform
import app.roadmaxxing.tracking.TrackingEngine
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformAppModule: Module = module {
    includes(notifyModule)
    single<DriveResultPoster> { AndroidDriveResultPoster(androidContext()) }
    single { TrackingEngine(androidContext(), recorder = get(), processor = get(), status = get(), notifier = get()) }
    single<TrackingPlatform> { AndroidTrackingPlatform(androidContext(), engine = get()) }
}
