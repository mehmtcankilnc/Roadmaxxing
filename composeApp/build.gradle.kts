import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// ---- App version: single source is gradle.properties (roadmaxxing.versionName) ----

/** Writes AppVersion.kt (package app.roadmaxxing.ui.profile) so common code can show the version. */
abstract class GenerateAppVersionTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.resolve("app/roadmaxxing/ui/profile")
        dir.mkdirs()
        dir.resolve("AppVersion.kt").writeText(
            """
            |package app.roadmaxxing.ui.profile
            |
            |// Generated from gradle.properties (roadmaxxing.versionName). Do not edit.
            |internal const val APP_VERSION = "${versionName.get()}"
            |""".trimMargin(),
        )
    }
}

/** Fails when the iOS MARKETING_VERSION in the Xcode project differs from versionName. */
abstract class CheckIosVersionTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pbxproj: RegularFileProperty

    @TaskAction
    fun check() {
        val found = Regex("""MARKETING_VERSION\s*=\s*([^;\s]+)\s*;""")
            .findAll(pbxproj.get().asFile.readText())
            .map { it.groupValues[1].trim('"') }
            .toList()
        val expected = versionName.get()
        if (found.isEmpty()) {
            throw GradleException("No MARKETING_VERSION found in iosApp/iosApp.xcodeproj/project.pbxproj. The iOS version must equal roadmaxxing.versionName ($expected) in gradle.properties.")
        }
        val wrong = found.filter { it != expected }
        if (wrong.isNotEmpty()) {
            throw GradleException(
                "App version mismatch: gradle.properties roadmaxxing.versionName is $expected but iOS MARKETING_VERSION in " +
                    "iosApp/iosApp.xcodeproj/project.pbxproj is ${wrong.distinct().joinToString()}. Make them equal.",
            )
        }
    }
}

val checkIosVersion = tasks.register<CheckIosVersionTask>("checkIosVersion") {
    group = "verification"
    description = "Fails if iOS MARKETING_VERSION differs from roadmaxxing.versionName."
    versionName.set(providers.gradleProperty("roadmaxxing.versionName"))
    pbxproj.set(rootProject.layout.projectDirectory.file("iosApp/iosApp.xcodeproj/project.pbxproj"))
}

val generateAppVersion = tasks.register<GenerateAppVersionTask>("generateAppVersion") {
    versionName.set(providers.gradleProperty("roadmaxxing.versionName"))
    outputDir.set(layout.buildDirectory.dir("generated/appVersion/kotlin"))
    // Every compilation (all targets) needs the generated file, so the iOS check runs with every build.
    dependsOn(checkIosVersion)
}

kotlin {
    android {
        namespace = "app.roadmaxxing.composeapp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        // Required so Compose Multiplatform resources are packaged into the Android app.
        androidResources {
            enable = true
        }
    }

    // The Xcode project links this static framework (see iosApp/iosApp.xcodeproj).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppVersion.flatMap { it.outputDir })
        }
        commonMain.dependencies {
            api(projects.shared)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.kotlinx.datetime)
            implementation(libs.maplibre.compose)
        }
        androidMain.dependencies {
            implementation(libs.play.services.location)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.koin.android)
            implementation(libs.kotlinx.coroutines.android)
            // Native map engine for Android (OpenGL ES; the Pixel emulators and older devices lack Vulkan guarantees).
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
        }
    }
}

compose.resources {
    packageOfResClass = "app.roadmaxxing.resources"
    publicResClass = false
}
