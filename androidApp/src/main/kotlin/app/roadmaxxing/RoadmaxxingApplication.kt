package app.roadmaxxing

import android.app.Application
import app.roadmaxxing.di.initKoin
import org.koin.android.ext.koin.androidContext

class RoadmaxxingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@RoadmaxxingApplication)
        }
    }
}
