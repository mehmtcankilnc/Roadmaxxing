package app.roadmaxxing.shared.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformModule: Module = module {
    single<SqlDriver> {
        NativeSqliteDriver(
            schema = RoadmaxxingDatabase.Schema,
            name = DATABASE_NAME,
        )
    }
}

private const val DATABASE_NAME = "roadmaxxing.db"
