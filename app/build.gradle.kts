import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fixed signing key, so a new version installs over the old one and keeps your bumps.
// On GitHub it comes from the repository secrets (see .github/workflows/build.yml); never commit the key file.
val releaseKeystore: String? = System.getenv("BUMP_KEYSTORE")
val releaseKeystorePassword: String? = System.getenv("BUMP_KEYSTORE_PASSWORD")
val hasReleaseKey = !releaseKeystore.isNullOrEmpty() && !releaseKeystorePassword.isNullOrEmpty()

android {
    namespace = "app.bumpbeeper"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.bumpbeeper"
        minSdk = 29          // Android 10+
        targetSdk = 34
        // Every GitHub build gets a higher number, so Android accepts it as an update.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.1.$build"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseKeystorePassword
                keyAlias = "bumpbeeper"
                keyPassword = releaseKeystorePassword
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without the secret (e.g. building in Android Studio) fall back to the debug key.
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The engine lives in :core (pure Kotlin). No other libraries in the app itself.
    implementation(project(":core"))
}
