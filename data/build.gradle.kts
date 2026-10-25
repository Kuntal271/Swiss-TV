plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.swissdark.launcher.data"
}

dependencies {
    api(project(":domain"))
    implementation(project(":platform:tv"))
    implementation(project(":core:perf"))
    implementation(libs.androidx.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.serialization.protobuf)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.work)
    testImplementation(libs.junit)
}
