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

// Research recordings (rr2, contracts/research-rr2.md) are read with the app's own reader, and the engine is fed through
// the app's RateAverager, exactly as BumpService does while research records. Both are plain Kotlin without Android, so
// they are copied (read-only) from the app at build time; no dependency on the Android module.
val appResearch = tasks.register<Copy>("appResearch") {
    from(rootProject.file("app/src/main/java/app/bumpbeeper/research/ResearchFormat.kt"))
    from(rootProject.file("app/src/main/java/app/bumpbeeper/research/RateAverager.kt"))
    into(layout.buildDirectory.dir("generated/appResearch"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(appResearch) }
tasks.named("compileKotlin") { dependsOn(appResearch) }

// The end-to-end tests drive core's simulator (gyroscope, tilted phone, lagging noisy GPS) and replay what it
// recorded. It lives in core's tests, so it is copied (read-only) into this module's test sources at build time,
// with its JVM random generator (core's `actual` keyword dropped: this module is plain Kotlin/JVM).
val coreSimulator = tasks.register<Copy>("coreSimulator") {
    from(rootProject.file("core/src/commonTest/kotlin/app/bumpbeeper/Simulator.kt"))
    from(rootProject.file("core/src/jvmTest/kotlin/app/bumpbeeper/SimRandom.jvm.kt")) {
        filter { line: String -> line.replace("actual ", "") }
    }
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
