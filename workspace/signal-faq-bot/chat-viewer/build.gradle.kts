plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    kotlin("plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
}

group = "de.services35"
version = "0.1.0"

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "signalfaqbot.chatviewer.MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Renders `Screenshot.kt`'s mock data to `screenshot.png` via Compose's
 * off-screen Skia rasterizer — no OS window or screen-capture permission
 * needed, see that file's doc comment.
 */
tasks.register<JavaExec>("screenshot") {
    group = "application"
    description = "Renders the chat viewer with mock data to screenshot.png"
    mainClass.set("signalfaqbot.chatviewer.ScreenshotKt")
    classpath = sourceSets["main"].runtimeClasspath
}
