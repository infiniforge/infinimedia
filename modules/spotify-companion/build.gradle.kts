plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.infinimedia.spotify"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    compileOnly(files("libs/spotify-app-remote-release-0.8.0-classes.jar"))
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("androidx.core:core-ktx:1.15.0")
}
