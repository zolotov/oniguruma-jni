import org.jetbrains.desktop.buildscripts.Arch
import org.jetbrains.desktop.buildscripts.CompileRustTask
import org.jetbrains.desktop.buildscripts.Os
import org.jetbrains.desktop.buildscripts.Platform
import org.jetbrains.desktop.buildscripts.currentPlatform

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jmh)
}

group = "me.zolotov.oniguruma"
version = (project.properties["version"] as? String)?.takeIf { it.isNotBlank() && it != "unspecified" } ?: "SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

val currentPlatform = currentPlatform()

val compileRustBindingsTaskByPlatform = listOf(
    Platform(Os.MACOS, Arch.aarch64),
    Platform(Os.WINDOWS, Arch.x86_64),
    Platform(Os.LINUX, Arch.x86_64)
).associateWith { platform ->
    tasks.register<CompileRustTask>("compileNative-${platform.os.normalizedName}-${platform.arch}") {
        val isOnCI = providers.environmentVariable("CI").orNull.toBoolean()
        val isTestMode = providers.environmentVariable("TEST_MODE").orNull.toBoolean()
        crateName = "oniguruma-jni"
        rustProfile = "release"
        rustTarget = platform
        nativeDirectory = layout.projectDirectory.dir("../native")
        enabled = (isOnCI && !isTestMode) || currentPlatform == platform
    }
}

val generateNativeResources = tasks.register<Sync>("generateResourcesDir") {
    destinationDir = layout.buildDirectory.dir("native").get().asFile

    compileRustBindingsTaskByPlatform.forEach { (platform, task) ->
        from(task.map { it.libraryFile }) {
            into("native/${platform.os.normalizedName}-${platform.arch}")
        }
    }
}

tasks.processResources {
    dependsOn(*compileRustBindingsTaskByPlatform.values.toTypedArray())
}

sourceSets {
    main {
        resources.srcDirs(generateNativeResources.map { it.destinationDir })
    }
}
