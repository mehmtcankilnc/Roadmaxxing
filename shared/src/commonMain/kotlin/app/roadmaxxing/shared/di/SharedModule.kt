package app.roadmaxxing.shared.di

import app.roadmaxxing.shared.data.DriveStore
import app.roadmaxxing.shared.data.SetupRepository
import app.roadmaxxing.shared.data.SqlDriveStore
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.domain.DriveProcessor
import app.roadmaxxing.shared.domain.ProgressRepository
import app.roadmaxxing.shared.domain.RegionPackProvider
import app.roadmaxxing.shared.domain.TraceMatcher
import app.roadmaxxing.shared.drive.DriveRecorder
import app.roadmaxxing.shared.drive.TrackingStatusHolder
import app.roadmaxxing.shared.map.DrivenRoadsProvider
import app.roadmaxxing.shared.matching.RoadMatcher
import app.roadmaxxing.shared.map.burn.BurnRepository
import app.roadmaxxing.shared.map.layers.MapLayerPreferences
import app.roadmaxxing.shared.map.undiscovered.UndiscoveredRoadsProvider
import app.roadmaxxing.shared.onboarding.IntroRoadsProvider
import app.roadmaxxing.shared.onboarding.SqlAppMetaStore
import app.roadmaxxing.shared.onboarding.OnboardingRepository
import app.roadmaxxing.shared.privacy.PrivacyZoneRepository
import app.roadmaxxing.shared.settings.DataEraser
import app.roadmaxxing.shared.settings.DataExporter
import app.roadmaxxing.shared.settings.SettingsRepository
import app.roadmaxxing.shared.summary.SummaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.module.Module
import org.koin.dsl.module

/** Platform specific bindings: the SQLDelight driver and the embedded region pack source. */
internal expect val platformModule: Module

val sharedModule: Module = module {
    includes(platformModule)
    single { RoadmaxxingDatabase(driver = get()) }
    single { SetupRepository(database = get(), ioDispatcher = Dispatchers.IO) }
    single<DriveStore> { SqlDriveStore(database = get(), ioDispatcher = Dispatchers.IO) }
    single { RegionPackProvider(source = get()) }
    single { OnboardingRepository(database = get()) }
    single { IntroRoadsProvider(packProvider = get()) }
    single { DriveRecorder(store = get()) }
    single { TrackingStatusHolder() }
    single {
        val packProvider: RegionPackProvider = get()
        DriveProcessor(
            store = get(),
            matcher = { TraceMatcher { trace -> RoadMatcher(packProvider.pack).match(trace) } },
            ioDispatcher = Dispatchers.IO,
            cpuDispatcher = Dispatchers.Default,
        )
    }
    single { ProgressRepository(store = get(), packProvider = get(), cpuDispatcher = Dispatchers.Default) }
    single {
        DrivenRoadsProvider(packProvider = get(), store = get(), dispatcher = Dispatchers.Default)
    }
    single { SettingsRepository(store = SqlAppMetaStore(get())) }
    single { DataExporter(store = get(), dispatcher = Dispatchers.Default) }
    single {
        DataEraser(
            database = get(), onboarding = get(), privacyZones = get(), layerPreferences = get(),
            settings = get(), drivenRoads = get(), dispatcher = Dispatchers.Default,
        )
    }
    single { MapLayerPreferences(store = SqlAppMetaStore(get())) }
    single {
        UndiscoveredRoadsProvider(packProvider = get(), store = get(), dispatcher = Dispatchers.Default)
    }
    single {
        BurnRepository(
            store = get(), packProvider = get(),
            appMeta = SqlAppMetaStore(get()),
            dispatcher = Dispatchers.Default,
        )
    }
    single {
        PrivacyZoneRepository(
            database = get(), store = get(), appMeta = SqlAppMetaStore(get()), dispatcher = Dispatchers.Default,
        )
    }
    single {
        val zoneRepository: PrivacyZoneRepository = get()
        SummaryRepository(
            packProvider = get(), store = get(),
            clock = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
            dispatcher = Dispatchers.Default,
            boundarySource = get(),
            privacyZones = { zoneRepository.zones.value },
        )
    }
}
