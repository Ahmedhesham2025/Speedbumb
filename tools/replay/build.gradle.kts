import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Replay + anonymizer CLI for real-road validation (docs/validation/). Paths are relative to the repository root:
//   ./gradlew :tools:replay:run --args="replay --trace run1.csv.gz --trace run2.csv.gz --out metrics.json"
//   ./gradlew :tools:replay:run --args="anonymize --in raw.csv --out anon.csv"
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// The end-to-end tests drive core's simulator (gyroscope, tilted phone, lagging noisy GPS) and replay what it
// recorded. It lives in core's tests, so it is copied (read-only) into this module's test sources at build time.
val coreSimulator = tasks.register<Copy>("coreSimulator") {
    from(rootProject.file("core/src/test/kotlin/app/bumpbeeper/Simulator.kt"))
    into(layout.buildDirectory.dir("generated/coreSimulator"))
}
kotlin.sourceSets.named("test") { kotlin.srcDir(coreSimulator) }
tasks.named("compileTestKotlin") { dependsOn(coreSimulator) }

application { mainClass.set("app.bumpbeeper.replay.MainKt") }
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

tasks.withType<Test>().configureEach {
    // Anonymized real drives (testdata/real) are replayed by RealDriveTest; a new or changed recording reruns it.
    val testdata = rootProject.file("testdata")
    systemProperty("testdata.dir", testdata.absolutePath)
    inputs.dir(testdata).withPropertyName("testdata").optional()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
