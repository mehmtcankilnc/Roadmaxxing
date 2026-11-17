package app.roadmaxxing.platform

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.roadmaxxing.tracking.IosTrackingEngine
import org.koin.mp.KoinPlatform
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

class IosTrackingPlatform(private val engine: IosTrackingEngine) : TrackingPlatform {

    /** True for Debug framework builds (Xcode configuration Debug => Gradle debug binary); false for Release/TestFlight. */
    @OptIn(ExperimentalNativeApi::class)
    override val isDebugBuild: Boolean = Platform.isDebugBinary

    override fun permissionStates(): Map<AppPermission, PermissionState> = engine.permissionStates()
    override fun onPermissionsChanged() = engine.onPermissionsChanged()
    override fun manualStart() = engine.manualStart()
    override fun manualStop() = engine.manualStop()
}

@Composable
actual fun rememberPermissionRequester(onResult: () -> Unit): (AppPermission) -> Unit {
    val current = rememberUpdatedState(onResult)
    val engine = remember { KoinPlatform.getKoin().get<IosTrackingEngine>() }
    return remember(engine) { { permission -> engine.request(permission) { current.value() } } }
}
