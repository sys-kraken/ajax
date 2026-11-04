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
    signingConfigs {
        getByName("debug") {
            System.getenv("AJAX_KEYSTORE")?.takeIf { it.isNotEmpty() }?.let {
                storeFile = file(it)
                storePassword = System.getenv("AJAX_KEYSTORE_PASSWORD")
                keyAlias = "ajax"
                keyPassword = System.getenv("AJAX_KEYSTORE_PASSWORD")
            }
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
    testImplementation("junit:junit:4.13.2")
}
