package app.roadmaxxing

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import app.roadmaxxing.di.initKoin
import app.roadmaxxing.tracking.IosTrackingEngine
import org.koin.mp.KoinPlatform

/**
 * Starts DI and the background drive tracking. Idempotent, main thread only.
 *
 * It runs from [MainViewController] (UI launch). iOS also relaunches the app in the background for a
 * significant location change or a region exit; whether the SwiftUI scene (and so MainViewController) is built in
 * that launch is not guaranteed, so Swift may call `IosBootstrapKt.startTrackingOnLaunch()` from `iOSApp.init()`
 * (one line; see the report). Not wired in Swift yet: pure Kotlin has no early hook in a SwiftUI app.
 */
fun startTrackingOnLaunch() {
    if (KoinPlatform.getKoinOrNull() == null) {
        initKoin()
    }
    KoinPlatform.getKoin().get<IosTrackingEngine>().start()
}
