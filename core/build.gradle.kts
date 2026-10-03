import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

// The Android-free engine (bump detection, driving monitor, geo math, model, phrases) as Kotlin Multiplatform, almost all
// in commonMain/commonTest. The Android app and tools/replay use the JVM target (the same classes as before); the iOS
// targets build the BumpCore framework, on a Mac only (CI: the ios-core job), and are skipped elsewhere.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // ./gradlew :core:assembleBumpCoreXCFramework → core/build/XCFrameworks/{debug,release}/BumpCore.xcframework
    val xcf = XCFramework("BumpCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "BumpCore"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonTest.dependencies {
            // kotlin.test: JUnit 4 underneath on the JVM, the native test runner on iOS.
            implementation(kotlin("test"))
        }
    }
}

// `./gradlew :core:test` keeps working: it runs the JVM tests (iOS: iosSimulatorArm64Test, Mac only).
if ("test" !in tasks.names) {
    tasks.register("test") {
        group = "verification"
        description = "Runs the core tests on the JVM."
        dependsOn("jvmTest")
    }
}

// Show why a simulated-drive test failed, right in the build log.
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// CI picks an installed simulator: -PiosSimulator=<udid>. Without it, Kotlin's default device is used.
providers.gradleProperty("iosSimulator").orNull?.let { sim ->
    tasks.withType<KotlinNativeSimulatorTest>().configureEach { device.set(sim) }
}
