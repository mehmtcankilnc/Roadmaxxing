package app.roadmaxxing

import android.app.Application
import app.roadmaxxing.di.initKoin
import app.roadmaxxing.firebase.FirebaseBootstrap
import app.roadmaxxing.firebase.initWith
import app.roadmaxxing.tracking.TrackingEngine
import org.koin.android.ext.koin.androidContext

class RoadmaxxingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before DI: stays off (isAvailable == false) when the build has no google-services.json.
        FirebaseBootstrap.initWith(this)
        val koin = initKoin {
            androidContext(this@RoadmaxxingApplication)
        }.koin
        // Every process start (UI, trigger broadcast, sticky service restart): resume an open drive,
        // process drives that ended while the process was dead, and re-arm the low-power triggers.
        koin.get<TrackingEngine>().onProcessStart()
    }
}
