plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.evolute.telemetrycollector"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.evolute.telemetrycollector"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.0"
    }
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            manifestPlaceholders["appLabel"] = "Evolute Telemetry Collector"
        }
        create("pro") {
            dimension = "edition"
            applicationIdSuffix = ".pro"
            versionNameSuffix = "-pro"
            manifestPlaceholders["appLabel"] = "Evolute Telemetry Collector Pro"
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
