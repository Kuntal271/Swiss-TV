plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.swissdark.launcher.feature.home"
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(project(":core:design"))
    implementation(project(":core:focus"))
    implementation(project(":core:perf"))
    implementation(project(":domain"))
    implementation(libs.coil.compose)
    implementation(libs.lifecycle.runtime.compose)
}
