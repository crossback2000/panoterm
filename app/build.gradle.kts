import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: keystore.properties lives OUTSIDE the repo
// (default ~/.panoterm/keystore.properties, or $PANOTERM_KEYSTORE_PROPS).
val signingProps = Properties().apply {
    val path = System.getenv("PANOTERM_KEYSTORE_PROPS")
        ?: "${System.getProperty("user.home")}/.panoterm/keystore.properties"
    val f = file(path)
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.panoterm"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.panoterm"
        minSdk = 31
        targetSdk = 35
        versionCode = 110
        versionName = "1.1.0"
        ndk { abiFilters += "arm64-v8a" } // Galaxy S25 Ultra
    }

    signingConfigs {
        if (signingProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // x86_64 lets the debug build run on the emulator (Go code does not run under ARM translation).
            ndk { abiFilters += "x86_64" }
        }
        release {
            // gomobile's JNI bridge looks classes up by name; keep R8 off.
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Built by scripts/build-core.ps1 (gomobile bind of ../core)
    implementation(files("libs/core.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
