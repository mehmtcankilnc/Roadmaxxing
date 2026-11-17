package app.roadmaxxing.shared.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.region.RegionPackSource
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.FileNotFoundException

internal actual val platformModule: Module = module {
    single<SqlDriver> {
        AndroidSqliteDriver(
            schema = RoadmaxxingDatabase.Schema,
            context = androidContext(),
            name = DATABASE_NAME,
        )
    }
    single<RegionPackSource> { AssetRegionPackSource(androidContext(), EMBEDDED_PACK_DIR) }
}

/** Phase 0: the Adana pack is embedded as APK assets under [dir] (see androidApp/build.gradle.kts). */
internal class AssetRegionPackSource(context: Context, private val dir: String) : RegionPackSource {
    private val assets = context.applicationContext.assets

    override fun read(path: String): ByteArray? = try {
        assets.open("$dir/$path").use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        null
    }
}

private const val DATABASE_NAME = "roadmaxxing.db"
private const val EMBEDDED_PACK_DIR = "regions/adana"
