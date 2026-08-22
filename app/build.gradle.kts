import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.transcripto.stream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.transcripto.stream"
        minSdk = 29
        targetSdk = 35
        versionCode = 15
        versionName = "0.4.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // 1) keystore.properties local (build hors CI : fichier gitignoré à la racine du projet)
            val props = File(rootProject.rootDir, "keystore.properties")
            if (props.exists()) {
                val p = Properties().apply { load(props.inputStream()) }
                storeFile = File(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
            // 2) env vars CI (secrets GitHub)
            val b64 = System.getenv("TRANSCRIPTO_STREAM_KEYSTORE_B64")
            if (storeFile == null && !b64.isNullOrBlank()) {
                val tmp = System.getenv("RUNNER_TEMP") ?: System.getProperty("java.io.tmpdir") ?: "/tmp"
                val ks = File(tmp, "transcripto-stream-release.keystore")
                ks.writeBytes(Base64.getDecoder().decode(b64))
                storeFile = ks
                storePassword = System.getenv("TRANSCRIPTO_STREAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TRANSCRIPTO_STREAM_KEY_ALIAS")
                keyPassword = System.getenv("TRANSCRIPTO_STREAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signingConfigs.getByName("release").storeFile == null) null
                else signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
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

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // Builds release locaux : ne pas bloquer sur des warnings lint pré-existants
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
