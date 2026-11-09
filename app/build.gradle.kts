plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ajax.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ajax.assistant"
        minSdk = 26
        targetSdk = 35
        // CI run numbers only go up, so every build can install over the previous one (100 clears earlier local builds).
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "0.7.0"

        // The on-device model needs a 64-bit ARM CPU; other phones simply don't get Stage 3.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    // Android only installs an update signed with the same key as the installed app, and a fresh CI
    // machine would make a new debug key every time. Same key = no uninstall, so the model is kept.
    val keystore = System.getenv("AJAX_KEYSTORE")?.takeIf { it.isNotEmpty() }
    signingConfigs {
        getByName("debug") {
            keystore?.let {
                storeFile = file(it)
                storePassword = System.getenv("AJAX_KEYSTORE_PASSWORD")
                keyAlias = "ajax"
                keyPassword = System.getenv("AJAX_KEYSTORE_PASSWORD")
            }
        }
        // The same key, so the release build installs over a debug build (and back) and keeps its data.
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("AJAX_KEYSTORE_PASSWORD")
                keyAlias = "ajax"
                keyPassword = System.getenv("AJAX_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        // The build for everyday use: not debuggable, so nothing can read Ajax's private storage over
        // a USB cable (voice recordings, voice print, settings). Code shrinking stays off: the speech
        // libraries find their classes by name at run time.
        getByName("release") {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Offline speech for the "Ajax" wake word; its model is downloaded once, like the language model.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // Runs the "Ajax" detector's feature models (openWakeWord's mel spectrogram and speech embedding).
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    testImplementation("junit:junit:4.13.2")
}
