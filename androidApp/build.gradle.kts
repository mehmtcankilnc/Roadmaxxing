plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no separate kotlin-android plugin is applied.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.roadmaxxing"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.roadmaxxing"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Single version source: gradle.properties (roadmaxxing.versionName / roadmaxxing.versionCode).
        versionCode = providers.gradleProperty("roadmaxxing.versionCode").get().toInt()
        versionName = providers.gradleProperty("roadmaxxing.versionName").get()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Phase 0: the Adana region pack is embedded in the APK. It is copied from test-data/regions/adana at build
// time into a generated asset directory (assets/regions/adana/...), so the files are not duplicated in the repo.
abstract class CopyRegionPackTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(packDir)
            into(outputDir.dir("regions/adana"))
        }
    }
}

val copyRegionPack = tasks.register<CopyRegionPackTask>("copyRegionPack") {
    packDir.set(rootProject.layout.projectDirectory.dir("test-data/regions/adana"))
    outputDir.set(layout.buildDirectory.dir("generated/regionPackAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyRegionPack, CopyRegionPackTask::outputDir)
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.android)
}
