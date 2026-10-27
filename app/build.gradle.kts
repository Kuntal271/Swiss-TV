import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.swissdark.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.swissdark.launcher"
        minSdk = 31
        targetSdk = 35
        // OTA: every build is strictly newer. Minutes since 2026-01-01 UTC; -PversionCode pins it.
        versionCode = (project.findProperty("versionCode") as String?)
            ?.toIntOrNull()
            ?: ((System.currentTimeMillis() / 60000L) - 29453760L).toInt()
        versionName = DateTimeFormatter.ofPattern("yyyy.MM.dd-HHmm")
            .withZone(ZoneId.of("UTC"))
            .format(Instant.now())
        // Optional: a TMDB v3 key baked in at build time. setup.sh can also push one at runtime.
        buildConfigField("String", "TMDB_KEY", "\"${localProps.getProperty("tmdb.key", "")}\"")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        // Sideloaded onto one TV: the debug key is enough and keeps updates installable.
        getByName("debug")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            proguardFiles("benchmark-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:focus"))
    implementation(project(":core:perf"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":platform:tv"))
    implementation(project(":feature:home"))
    implementation(project(":feature:apps"))
    implementation(project(":feature:search"))
    implementation(project(":feature:title"))
    implementation(project(":feature:panels"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:screensaver"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.androidx.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.okhttp)
    implementation(libs.work)
    implementation(libs.profileinstaller)
    debugImplementation(libs.compose.ui.tooling)
}
