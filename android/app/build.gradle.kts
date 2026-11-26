plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Built-in Tailscale: the Go node as an AAR from the :tailnet module (gomobile bind on the build host). Without an NDK to build it
// with, the app builds without it (src/noTailnet) and Settings says so.
evaluationDependsOn(":tailnet")
val tailnet = project(":tailnet")
val tailnetLinked = tailnet.extra["tailnetLinked"] as Boolean

android {
    namespace = "dev.leash.personal"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.leash.personal"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
            isMinifyEnabled = false
        }
    }

    if (tailnetLinked) ndkVersion = tailnet.extra["ndkVersion"] as String
    sourceSets.getByName("main").java.srcDir(if (tailnetLinked) "src/tailnet/java" else "src/noTailnet/java")

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
    if (tailnetLinked) {
        debugImplementation(files(tailnet.extra["tailnetAarDebug"]!!).builtBy(":tailnet:bindTailnetDebug"))
        releaseImplementation(files(tailnet.extra["tailnetAarRelease"]!!).builtBy(":tailnet:bindTailnetRelease"))
    }
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
