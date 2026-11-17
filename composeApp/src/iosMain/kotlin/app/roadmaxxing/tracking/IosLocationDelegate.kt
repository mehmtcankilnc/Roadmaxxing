package app.roadmaxxing.tracking

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.CLRegion
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * CoreLocation delegate: a plain NSObject subclass that forwards to [IosTrackingEngine]. CLLocationManager keeps
 * only an unretained reference, so the engine owns this object for the life of the process.
 * Callbacks arrive on the thread that created the managers (the main thread).
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal class IosLocationDelegate(private val engine: IosTrackingEngine) : NSObject(), CLLocationManagerDelegateProtocol {

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        engine.onLocations(manager, didUpdateLocations.filterIsInstance<CLLocation>())
    }

    override fun locationManager(manager: CLLocationManager, didExitRegion: CLRegion) {
        engine.onRegionExit(manager)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        engine.onLocationError(didFailWithError.localizedDescription)
    }

    // iOS 14+: also called once right after the delegate is set.
    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        if (manager === engine.idleManager) engine.onAuthorizationChanged()
    }
}
