import java.util.Properties

/*
 * Release signing, read from a keystore.properties that is deliberately not in
 * the repository. The signing key is what proves an update came from the same
 * author, so whoever holds it can publish something Android will install over
 * this app. A clone without it still builds; the APK is just unsigned, which is
 * enough to compile and test and not enough to install.
 */
val signing = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mavgcs.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mavgcs.app"
        minSdk = 26
        targetSdk = 35
        // The code has to climb for Android to see an update at all; the name
        // is what people read.
        versionCode = 14
        versionName = "1.1.0"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Left off on purpose: message ids are read from the dialect's own
            // runtime annotations rather than written out here, and shrinking
            // is free to throw those away.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.dronefleet.mavlink:mavlink:1.1.11")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Serves the bundled Cesium build from a real origin, so the page is
    // subject to ordinary CORS rather than needing the filesystem opened up.
    implementation("androidx.webkit:webkit:1.12.1")
    // Live video. The RTSP source is a separate artifact from the player, and
    // both are needed: the player alone cannot open an rtsp:// address.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
