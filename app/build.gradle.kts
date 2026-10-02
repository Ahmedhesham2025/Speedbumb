import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Fixed signing key, so a new version installs over the old one and keeps your bumps.
// On GitHub it comes from the repository secrets (see .github/workflows/release.yml); never commit the key file.
val releaseKeystore: String? = System.getenv("BUMP_KEYSTORE")
val releaseKeystorePassword: String? = System.getenv("BUMP_KEYSTORE_PASSWORD")
val hasReleaseKey = !releaseKeystore.isNullOrEmpty() && !releaseKeystorePassword.isNullOrEmpty()

// The version comes from the release tag (release.yml sets VERSION_NAME, e.g. "1.2.0").
// versionCode = MAJOR*10000 + MINOR*100 + PATCH, so 1.2.0 → 10200: always above the old run-number builds (≤ ~17),
// and every later tag installs over the earlier one.
val versionNameFromTag: String? = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
val versionCodeFromTag: Int = versionNameFromTag?.let { name ->
    val m = Regex("""^(\d+)\.(\d{1,2})\.(\d{1,2})$""").matchEntire(name)
        ?: throw GradleException("VERSION_NAME '$name' must look like 1.2.0 (minor and patch below 100)")
    val (major, minor, patch) = m.destructured
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
} ?: 1

android {
    namespace = "app.bumpbeeper"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.bumpbeeper"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = versionCodeFromTag
        versionName = versionNameFromTag ?: "0.0.0-local"

        // Hosted backend (Supabase). Both values are PUBLIC by design: the publishable key is meant to ship inside apps,
        // and Row Level Security protects the data. Never put a service-role/secret key here.
        buildConfigField("String", "SUPABASE_URL", "\"https://gpefcdyuipmspezbsano.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_Oo5L_qbPGCBzXVSlyFz5ow_I6vFuhRl\"")
    }

    buildFeatures {
        buildConfig = true
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
        // PR test builds install next to the real app (app.bumpbeeper.dev) and never touch its data.
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
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

    // Robolectric needs the merged resources; unmocked android.* calls return defaults instead of throwing.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Show why an app unit test failed, right in the build log.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

dependencies {
    // The engine lives in :core (pure Kotlin). No other libraries in the app itself.
    implementation(project(":core"))

    // Test-only: never shipped in the APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    // The real org.json, so JSON code runs in plain JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
