import java.util.zip.ZipFile
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
    // 21.4.0 ships Kotlin 2.3 metadata and needs Kotlin 2.3 (we use 2.1.21, which reads the Kotlin 2.2 metadata of MapLibre 13.6): bump both together.
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

tasks.register("printTestCp") {
    doLast {
        val cfg = configurations.getByName("fossDebugUnitTestCompileClasspath")
        val files = cfg.incoming.artifactView { attributes { attribute(org.gradle.api.attributes.Attribute.of("artifactType", String::class.java), "android-classes-jar") } }.files.files
        files.forEach { f ->
            val hasJson = f.isFile && ZipFile(f).use { z -> z.getEntry("org/json/JSONObject.class") != null }
            println("CP ${f.name} ${if (hasJson) "HAS_ORG_JSON" else ""}")
        }
    }
}
afterEvaluate {
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileFossDebugUnitTestKotlin") {
        doFirst { libraries.files.forEach { f -> val j = f.isFile && f.name.endsWith(".jar") && ZipFile(f).use { z -> z.getEntry("org/json/JSONObject.class") != null }; println("KLIB ${f.name} ${if (j) "HAS_ORG_JSON" else ""}") } }
    }
}
