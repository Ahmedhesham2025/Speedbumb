import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Replay + anonymizer CLI for real-road validation (docs/validation/). Paths are relative to the repository root:
//   ./gradlew :tools:replay:run --args="replay --trace drive.csv.gz --out metrics.json"
//   ./gradlew :tools:replay:run --args="anonymize --in raw.csv --out anon.csv"
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

application { mainClass.set("app.bumpbeeper.replay.MainKt") }
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }

tasks.withType<Test>().configureEach {
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
