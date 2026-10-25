plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.swissdark.launcher.platform.tv"
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.androidx.core)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation("net.sf.kxml:kxml2:2.3.0")   // an XmlPullParser for the appfilter tests
}
