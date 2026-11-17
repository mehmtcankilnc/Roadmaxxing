package app.roadmaxxing.shared.di

// NOT VERIFIED on CI yet: iosMain cannot be compiled on the developer's Windows machine.

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.roadmaxxing.shared.db.RoadmaxxingDatabase
import app.roadmaxxing.shared.region.RegionPackSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

internal actual val platformModule: Module = module {
    single<SqlDriver> {
        NativeSqliteDriver(
            schema = RoadmaxxingDatabase.Schema,
            name = DATABASE_NAME,
        )
    }
    single<RegionPackSource> { BundleRegionPackSource(EMBEDDED_PACK_DIR) }
}

/**
 * Phase 0: the Adana pack is copied into the app bundle under [dir] by the Xcode "Copy Region Pack" build phase
 * (iosApp/iosApp.xcodeproj, from test-data/regions/adana). Same relative layout as the Android assets.
 */
internal class BundleRegionPackSource(dir: String) : RegionPackSource {
    private val root: String? = NSBundle.mainBundle.resourcePath?.let { "$it/$dir" }

    override fun read(path: String): ByteArray? {
        val base = root ?: return null
        return NSData.dataWithContentsOfFile("$base/$path")?.toByteArray()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

private const val DATABASE_NAME = "roadmaxxing.db"
private const val EMBEDDED_PACK_DIR = "regions/adana"
