plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.swissdark.launcher.domain"
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    api(libs.compose.runtime)
    api(libs.immutable)
    api(libs.coroutines.android)
    api(libs.serialization.protobuf)
    testImplementation(libs.junit)
}
