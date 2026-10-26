plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.swissdark.launcher.core.design"
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    api(libs.compose.runtime)
    api(libs.immutable)
    api(project(":core:focus"))
    api(project(":domain"))
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
}
