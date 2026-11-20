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

/**
 * Writes GeneratedFirebaseConfig.kt (androidMain) from the git-ignored androidApp/google-services.json, so Firebase can be
 * initialised from code without the Google Services Gradle plugin. Without the file every value is empty and
 * FirebaseBootstrap.isAvailable stays false. The output lives in build/ (git-ignored) and values are never logged.
 */
@DisableCachingByDefault(because = "The output can contain project identifiers; never store it in a build cache.")
abstract class GenerateFirebaseConfigTask : DefaultTask() {
    /** google-services.json when present (the collection is empty or points at a missing file otherwise). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val configFile: ConfigurableFileCollection

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = configFile.files.firstOrNull { it.isFile }
        var appId = ""
        var apiKey = ""
        var projectId = ""
        var projectNumber = ""
        var storageBucket = ""
        var webClientId = ""
        if (file != null) {
            @Suppress("UNCHECKED_CAST")
            val root = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
            fun str(v: Any?): String = (v as? String).orEmpty()
            val info = root["project_info"] as? Map<String, Any?>
            projectId = str(info?.get("project_id"))
            projectNumber = str(info?.get("project_number"))
            storageBucket = str(info?.get("storage_bucket"))
            val clients = (root["client"] as? List<Map<String, Any?>>).orEmpty()
            val client = clients.firstOrNull {
                val ci = it["client_info"] as? Map<String, Any?>
                val android = ci?.get("android_client_info") as? Map<String, Any?>
                str(android?.get("package_name")) == applicationId.get()
            } ?: clients.firstOrNull()
            if (client != null) {
                appId = str((client["client_info"] as? Map<String, Any?>)?.get("mobilesdk_app_id"))
                apiKey = str((client["api_key"] as? List<Map<String, Any?>>)?.firstOrNull()?.get("current_key"))
                // oauth_client type 3 is the Web client; Credential Manager needs it as the server client id.
                webClientId = (client["oauth_client"] as? List<Map<String, Any?>>).orEmpty()
                    .firstOrNull { (it["client_type"] as? Number)?.toInt() == 3 }
                    ?.let { str(it["client_id"]) }.orEmpty()
            }
        }
        // Only a complete config counts: a half-filled file must not half-initialise Firebase.
        if (appId.isEmpty() || apiKey.isEmpty() || projectId.isEmpty()) {
            appId = ""; apiKey = ""; projectId = ""; projectNumber = ""; storageBucket = ""
        }
        fun lit(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        val dir = outputDir.get().asFile.resolve("app/roadmaxxing/firebase")
        dir.mkdirs()
        dir.resolve("GeneratedFirebaseConfig.kt").writeText(
            """
            |package app.roadmaxxing.firebase
            |
            |// Generated from androidApp/google-services.json (git-ignored). Do not edit, do not commit.
            |internal object GeneratedFirebaseConfig {
            |    const val APPLICATION_ID = ${lit(appId)}
            |    const val API_KEY = ${lit(apiKey)}
            |    const val PROJECT_ID = ${lit(projectId)}
            |    const val GCM_SENDER_ID = ${lit(projectNumber)}
            |    const val STORAGE_BUCKET = ${lit(storageBucket)}
            |    const val GOOGLE_WEB_CLIENT_ID = ${lit(webClientId)}
            |}
            |""".trimMargin(),
        )
    }
}

val generateFirebaseConfig = tasks.register<GenerateFirebaseConfigTask>("generateFirebaseConfig") {
    configFile.from(rootProject.layout.projectDirectory.file("androidApp/google-services.json"))
    applicationId.set("app.roadmaxxing")
    outputDir.set(layout.buildDirectory.dir("generated/firebaseConfig/kotlin"))
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
        commonMain.dependencies {
            // Firebase (GitLive). composeApp only: :shared stays Firebase-free so its iOS tests need no Firebase SDK.
            implementation(libs.gitlive.firebase.app)
            implementation(libs.gitlive.firebase.auth)
            implementation(libs.gitlive.firebase.firestore)
        }
        androidMain {
            kotlin.srcDir(generateFirebaseConfig.flatMap { it.outputDir })
        }
        androidMain.dependencies {
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.play.services.auth)
            implementation(libs.google.id)
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
