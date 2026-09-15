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
        versionCode = 24
        versionName = "0.10.0"

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
            // R8 : code réduit et obscurci, ressources inutilisées retirées (règles : proguard-rules.pro).
            // La CI compile la release non signée pour attraper une règle manquante avant publication.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    testOptions {
        // Les tests JVM traversent des chemins qui journalisent (android.util.Log) :
        // valeurs par défaut plutôt que « Method not mocked »
        unitTests.isReturnDefaultValues = true
        // Robolectric : ressources et manifeste disponibles aux tests qui en ont besoin
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // SDK Anthropic (OkHttp + Jackson) : métadonnées dupliquées entre jars
            excludes += "META-INF/versions/**"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/INDEX.LIST"
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
    // Déverrouillage biométrique (BiometricPrompt ; MainActivity doit être une FragmentActivity).
    // fragment ≥ 1.3 explicitement : la 1.2.5 tirée par biometric refuse les codes de requête
    // (≥ 0x10000) des lanceurs Activity Result de Compose (permissions, sélecteurs SAF).
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // Détection de parole neuronale Silero VAD (modèle ONNX embarqué dans assets/vad)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // Synthèse IA (opt-in) : SDK Java officiel Anthropic — seule la transcription est envoyée
    implementation("com.anthropic:anthropic-java:2.62.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Parties Android testées en JVM (journal des plantages, contexte applicatif)
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
