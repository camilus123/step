plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Version code: the CI run number, so every build installs cleanly over the previous one.
// Version name: the tag (v0.1 -> 0.1) for tagged builds, otherwise "dev-<run number>".
val runNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
val tagName = providers.environmentVariable("GITHUB_REF_NAME").orNull
    ?.takeIf { providers.environmentVariable("GITHUB_REF_TYPE").orNull == "tag" }
    ?.removePrefix("v")

// Signing: supplied through environment variables (GitHub Actions secrets in CI).
// Without them the release build is left unsigned and cannot be installed, on purpose:
// an APK signed with any other key could not be updated later without wiping the data.
val keystoreFile = providers.environmentVariable("STEP_KEYSTORE_FILE").orNull

android {
    namespace = "si.marko.step"
    // Compiled against 37 because current AndroidX libraries require it.
    // targetSdk stays 36 (Android 16), which is what decides runtime behaviour.
    compileSdk = 37

    defaultConfig {
        applicationId = "si.marko.step"
        minSdk = 29
        targetSdk = 36
        versionCode = runNumber ?: 1
        versionName = tagName ?: "dev-${runNumber ?: "local"}"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = providers.environmentVariable("STEP_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("STEP_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("STEP_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // No code shrinking: the app is tiny, and readable stack traces matter more
            // when the only test device is a phone the developer cannot see.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
