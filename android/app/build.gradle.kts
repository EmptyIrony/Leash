import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Built-in Tailscale: the Go node as an AAR from the :tailnet module (gomobile bind on the build host). Without an NDK to build it
// with, the app builds without it (src/noTailnet) and Settings says so.
evaluationDependsOn(":tailnet")
val tailnet = project(":tailnet")
val tailnetLinked = tailnet.extra["tailnetLinked"] as Boolean

// Release signing: android/signing/keystore.properties (git-ignored, see PROGRESS.md) names the keystore; without it, or without the
// keystore it points to, the release APK is left unsigned so a fresh checkout still builds.
val signingDir = rootProject.layout.projectDirectory.dir("signing").asFile
val signingProps = Properties().apply {
    val f = File(signingDir, "keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val releaseKeystore: File? = signingProps.getProperty("storeFile")?.let { File(signingDir, it) }   // relative to signing/, so it works on any host
val releaseSigned = releaseKeystore?.isFile == true
if (!releaseSigned) {
    logger.warn(
        if (signingProps.isEmpty) "WARNING: ${signingDir.path}${File.separator}keystore.properties not found: the release APK will be UNSIGNED"
        else "WARNING: release keystore ${releaseKeystore?.path} not found: the release APK will be UNSIGNED"
    )
}

android {
    namespace = "dev.leash.personal"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.leash.personal"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseSigned) create("release") {
            storeFile = releaseKeystore
            storeType = signingProps.getProperty("storeType") ?: "pkcs12"
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // the Go node (and so the APK) is x86_64 only for the emulator; release is arm64 only
            ndk { abiFilters += "x86_64" }
            // lets a second debug build (e.g. -PleashIdSuffix=.design) install beside the main one on a shared emulator
            applicationIdSuffix = providers.gradleProperty("leashIdSuffix").orNull
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigned) signingConfig = signingConfigs.getByName("release")
        }
        // Release's shrinking (R8 + resource shrinking + the same proguard rules) on an installable x86_64 build: release itself is arm64
        // only and release-signed, so it cannot run on the emulator. Same code as debug (src/debug: demo bridge, scripted Tailscale node)
        // but not debuggable, debug-signed, with its own id (.releaselike) so it installs beside debug.
        // android_build install=true tasks=["assembleReleaseLike"] apk_filter="releaseLike"
        create("releaseLike") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".releaselike"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets.getByName("releaseLike").run {
        kotlin.directories += "src/debug/java"
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }
    if (tailnetLinked) ndkVersion = tailnet.extra["ndkVersion"] as String
    sourceSets.getByName("main").kotlin.directories += if (tailnetLinked) "src/tailnet/java" else "src/noTailnet/java"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Prints the signing certificates of the built release APK (apksigner from the SDK's build-tools): ./gradlew verifyReleaseSignature
tasks.register<Exec>("verifyReleaseSignature") {
    group = "verification"
    description = "apksigner verify --print-certs on the release APK (builds it first)"
    dependsOn("assembleRelease")
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val sdk = androidComponents.sdkComponents.sdkDirectory
    doFirst {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val tool = sdk.get().asFile.resolve("build-tools").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.reversed()
            .map { it.resolve(if (windows) "apksigner.bat" else "apksigner") }.first { it.isFile }
        commandLine(tool.absolutePath, "verify", "--verbose", "--print-certs", apk.get().asFile.absolutePath)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    if (tailnetLinked) {
        // builtBy takes the task provider, not its path: looking a path up while a task runs deadlocks Gradle 9 (lintVital did)
        val bindDebug = tailnet.tasks.named("bindTailnetDebug")
        val bindRelease = tailnet.tasks.named("bindTailnetRelease")
        debugImplementation(files(tailnet.extra["tailnetAarDebug"]!!).builtBy(bindDebug))
        releaseImplementation(files(tailnet.extra["tailnetAarRelease"]!!).builtBy(bindRelease))
        "releaseLikeImplementation"(files(tailnet.extra["tailnetAarDebug"]!!).builtBy(bindDebug))
    }
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
