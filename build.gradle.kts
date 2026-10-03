plugins {
    id("com.android.application") version "8.7.3" apply false
    // 2.2.21: MapLibre 13.6 (the street map, v1.8) ships Kotlin 2.2 metadata. Keep all three Kotlin plugins in step.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.21" apply false
}
