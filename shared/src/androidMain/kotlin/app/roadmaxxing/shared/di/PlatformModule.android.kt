package app.roadmaxxing.shared.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformModule: Module = module {
    single<SqlDriver> {
        AndroidSqliteDriver(
            schema = RoadmaxxingDatabase.Schema,
            context = androidContext(),
            name = DATABASE_NAME,
        )
    }
}

private const val DATABASE_NAME = "roadmaxxing.db"
