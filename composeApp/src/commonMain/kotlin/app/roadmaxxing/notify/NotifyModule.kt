package app.roadmaxxing.notify

import org.koin.core.module.Module
import org.koin.dsl.module

/** Common bindings of the end-of-drive notification. The platform module provides [DriveResultPoster]. */
internal val notifyModule: Module = module {
    single { PendingDriveNavigation(onboarding = get()) }
    single { DriveResultNotifier(store = get(), poster = get()) }
}
