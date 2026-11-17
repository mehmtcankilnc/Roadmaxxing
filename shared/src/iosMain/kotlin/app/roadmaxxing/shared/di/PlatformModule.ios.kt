package app.roadmaxxing.shared.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.region.RegionPackSource
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformModule: Module = module {
    single<SqlDriver> {
        NativeSqliteDriver(
            schema = RoadmaxxingDatabase.Schema,
            name = DATABASE_NAME,
        )
    }
    // NOT VERIFIED (cannot compile iosMain on Windows). Placeholder until the pack is bundled into the iOS app:
    // every read returns null, so the region pack is unavailable on iOS for now.
    single<RegionPackSource> { RegionPackSource { null } }
}

private const val DATABASE_NAME = "roadmaxxing.db"
