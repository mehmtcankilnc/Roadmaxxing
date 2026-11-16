package app.roadmaxxing.shared.di

import app.roadmaxxing.shared.data.SetupRepository
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.core.module.Module
import org.koin.dsl.module

/** Platform specific bindings, e.g. the SQLDelight driver. */
internal expect val platformModule: Module

val sharedModule: Module = module {
    includes(platformModule)
    single { RoadmaxxingDatabase(driver = get()) }
    single { SetupRepository(database = get(), ioDispatcher = Dispatchers.IO) }
}
