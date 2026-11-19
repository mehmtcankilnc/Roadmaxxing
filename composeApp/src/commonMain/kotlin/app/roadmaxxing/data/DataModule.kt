package app.roadmaxxing.data

import org.koin.core.module.Module
import org.koin.dsl.module

/** Binds [DataActions]. Included by the platform modules, which bind [DataPlatform]. */
internal val dataModule: Module = module {
    single<DataActions> {
        DefaultDataActions(exporter = get(), eraser = get(), platform = get(), navigation = get())
    }
}
