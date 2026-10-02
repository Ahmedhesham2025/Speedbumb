import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Android-free engine: bump detection, driving monitor, geo math, model and spoken phrases.
// Plain Kotlin/JVM, so it builds and tests without the Android SDK.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Show why a simulated-drive test failed, right in the build log.
tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

dependencies {
    // JUnit only for the simulated-drive tests.
    testImplementation("junit:junit:4.13.2")
}
