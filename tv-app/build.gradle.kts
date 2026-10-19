plugins {
    id("com.android.application")
}

android {
    namespace = "com.tvscreentime.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tvscreentime.tv"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    packaging {
        resources.excludes += listOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("io.ktor:ktor-server-cio:3.6.0")
    implementation("io.ktor:ktor-server-websockets:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
