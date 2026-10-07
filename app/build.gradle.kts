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

// The version comes from the release tag (release.yml sets VERSION_NAME): "1.8.0" for a stable release, "1.8.0-beta1"
// for a beta (published as a GitHub pre-release).
// versionCode = (MAJOR*10000 + MINOR*100 + PATCH) * 100 + (beta N, or 99 for a stable release), so
// 1.7.1 → 1070199, 1.8.0-beta1 → 1080001, 1.8.0-beta2 → 1080002, 1.8.0 → 1080099. Each stable release is above its own
// betas, each beta is above the stable release before it, and all are above the old codes (MAJOR*10000 + MINOR*100 +
// PATCH: 1.7.1 was 10701), so every release installs over the one before.
fun versionCodeOf(versionName: String): Int {
    val m = Regex("""(\d{1,3})\.(\d{1,2})\.(\d{1,2})(?:-beta([1-9]\d?))?""").matchEntire(versionName)
    val beta = m?.groupValues?.get(4)?.toIntOrNull()
    if (m == null || (beta != null && beta > 98)) {
        throw GradleException(
            "VERSION_NAME '$versionName' must look like 1.2.0 or 1.2.0-beta1 " +
                "(major below 1000, minor and patch below 100, beta 1 to 98)"
        )
    }
    val (major, minor, patch) = m.destructured
    return (major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()) * 100 + (beta ?: 99)
}

val versionNameFromTag: String? = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
val versionCodeFromTag: Int = versionNameFromTag?.let { versionCodeOf(it) } ?: 1

// The scheme above on fixed examples. It runs before every app build (preBuild), so CI and release.yml run it too.
// On its own: ./gradlew :app:checkVersionCodes
val checkVersionCodes = tasks.register("checkVersionCodes") {
    group = "verification"
    description = "Checks the release tag → versionCode scheme on fixed examples."
    val expected = mapOf(
        "1.7.1" to 1070199, "1.8.0-beta1" to 1080001, "1.8.0-beta98" to 1080098, "1.8.0" to 1080099,
        "1.10.0" to 1100099, "2.0.0-beta1" to 2000001,
    )
    // Worked out here, so the action below only compares plain values.
    val actual = expected.keys.associateWith { runCatching { versionCodeOf(it) }.getOrNull() }
    val accepted = listOf("1.8.0-beta0", "1.8.0-beta99", "1.8.0-beta01", "1.8.0-rc1", "1.8", "1.100.0", "v1.8.0")
        .filter { runCatching { versionCodeOf(it) }.isSuccess }
    val thisBuild = versionNameFromTag?.let { "; this build: $it → $versionCodeFromTag" } ?: ""
    doLast {
        val problems = expected.filter { (name, code) -> actual[name] != code }
            .map { (name, code) -> "$name → ${actual[name]}, expected $code" } +
            accepted.map { "$it should be rejected" }
        if (problems.isNotEmpty()) throw GradleException("versionCode scheme broken:\n" + problems.joinToString("\n"))
        logger.lifecycle("versionCode scheme OK: " + actual.entries.joinToString { "${it.key} → ${it.value}" } + thisBuild)
    }
}
tasks.named("preBuild") { dependsOn(checkVersionCodes) }

android {
    namespace = "app.bumpbeeper"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.bumpbeeper"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = versionCodeFromTag
        versionName = versionNameFromTag ?: "0.0.0-local"

        // Emulator tests (app/src/androidTest): only the screenshot run in .github/workflows/screenshots.yml so far.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Hosted backend (Supabase). Both values are PUBLIC by design: the publishable key is meant to ship inside apps,
        // and Row Level Security protects the data. Never put a service-role/secret key here.
        buildConfigField("String", "SUPABASE_URL", "\"https://gpefcdyuipmspezbsano.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_Oo5L_qbPGCBzXVSlyFz5ow_I6vFuhRl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Two editions from the same code (issue #48). Same applicationId and key: a user installs one or the other.
    // foss: Android platform APIs + :core + MapLibre (the street map, v1.8) only (GitHub Releases, F-Droid, AppGallery);
    // CI fails if it gains any other library.
    // play: adds Google Play services activity recognition, falling back to the foss detection without Google services.
    flavorDimensions += "dist"
    productFlavors {
        create("foss") {
            dimension = "dist"
        }
        create("play") {
            dimension = "dist"
            versionNameSuffix = "-google"
        }
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

    // MapLibre's native map engine (~40 MB for all 4 ABIs uncompressed). Compressing it in the APK roughly halves the
    // download; Android unpacks it once at install. Per-ABI APKs would shrink it further (see the v1.8 PR notes).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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

// Unit tests use the real org.json (JSONObject.keySet() etc.), but android.jar's stub JSONObject sits first on the
// Kotlin compiler's classpath since the MapLibre libraries joined it. Put org.json in front for test compiles only.
val orgJsonForTests: Configuration = configurations.detachedConfiguration(dependencies.create("org.json:json:20240303"))
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("UnitTest")) {
        doFirst { libraries.setFrom(orgJsonForTests.files + libraries.files) }
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
    // The engine lives in :core (pure Kotlin). No other libraries in the app itself, except the two below.
    implementation(project(":core"))
    // The street map (owner decision 2026-10-03): MapLibre Native, BSD-2, both editions, with free OpenFreeMap tiles
    // (no key, no account). The OpenGL ES build, not the Vulkan default, so older and budget phones (and the CI
    // emulator) can draw it. CI's foss guard allows exactly this and its transitive libraries.
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    // The ONE allowed library, play edition only (owner decision, #48). The foss edition must never get one.
    // 21.4.0 ships Kotlin 2.3 metadata and needs Kotlin 2.3 (we use 2.2.21, which MapLibre 13.6 needs): bump both together.
    "playImplementation"("com.google.android.gms:play-services-location:21.3.0")

    // Test-only: never shipped in the APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    // The real org.json, so JSON code runs in plain JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")

    // Emulator tests only (store screenshots), never shipped in the APK.
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
