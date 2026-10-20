plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tvscreentime.parent"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.tvscreentime.parent"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    // ZXing scanner: works without Google Play Services (e.g. Huawei tablets).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
