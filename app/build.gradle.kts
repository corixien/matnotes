plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.matnotes.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.matnotes.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.2.1"
        ndk { abiFilters += "arm64-v8a" }
        // Sync server (Render). Override for a local server: -PsyncUrl=http://127.0.0.1:3000/api (debug builds allow cleartext to localhost)
        val syncUrl = (project.findProperty("syncUrl") as String?) ?: System.getenv("MATNOTES_SYNC_URL")?.takeIf { it.isNotBlank() } ?: "https://matnotes-sync.onrender.com/api"
        buildConfigField("String", "SYNC_URL", "\"$syncUrl\"")
    }

    val ksFile = System.getenv("MATNOTES_KEYSTORE")
    signingConfigs {
        if (ksFile != null) create("release") {
            storeFile = file(ksFile)
            storePassword = System.getenv("MATNOTES_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MATNOTES_KEY_ALIAS")
            keyPassword = System.getenv("MATNOTES_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug {
            // lives next to the signed release app (different signature): `com.matnotes.app.debug`
            applicationIdSuffix = ".debug"
        }
        release {
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
