import org.jetbrains.desktop.buildscripts.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jmh)
    id("maven-publish")
    id("signing")
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
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

val currentPlatform = currentPlatform()

val compileRustBindingsTaskByPlatform = listOf(
    Platform(Os.MACOS, Arch.aarch64),
    Platform(Os.MACOS, Arch.x86_64),
    Platform(Os.WINDOWS, Arch.aarch64),
    Platform(Os.WINDOWS, Arch.x86_64),
    Platform(Os.LINUX, Arch.aarch64),
    Platform(Os.LINUX, Arch.x86_64)
).associateWith { platform ->
    tasks.register<CompileRustTask>("compileNative-${buildPlatformRustTarget(platform)}") {
        crateName = "oniguruma-jni"
        rustProfile = "release"
        rustTarget = platform
        nativeDirectory = layout.projectDirectory.dir("../native")
        enabled = when (providers.environmentVariable("NATIVE_BUILD_MODE").orNull) {
            "skip" -> false
            "all" -> true
            else -> currentPlatform == platform
        }
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

// Configure the sourcesJar task to exclude resources
tasks.named<Jar>("sourcesJar") {
    exclude("**/native")
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("Alexander Zolotov")
                description.set("""
                    A JNI wrapper for the Oniguruma regular expression library, with Rust implementation using the onig crate.
                    This library is primarily designed to support syntax highlighting in IntelliJ-based IDEs through the textmate-core library.
                """.trimIndent())
                url.set("https://github.com/zolotov/oniguruma-jni")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("zolotov")
                        name.set("Alexander Zolotov")
                        email.set("goldifit@gmail.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/zolotov/oniguruma-jni.git")
                    developerConnection.set("scm:git:ssh://github.com/zolotov/oniguruma-jni.git")
                    url.set("https://github.com/zolotov/oniguruma-jni")
                }
            }
        }
    }

    repositories {
        maven {
            name = "OSSRH"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            credentials {
                username = project.properties["ossrhUsername"] as String? ?: System.getenv("OSSRH_USERNAME")
                password = project.properties["ossrhPassword"] as String? ?: System.getenv("OSSRH_PASSWORD")
            }
        }
    }
}

signing {
    val signingKey = project.properties["signing.key"] as String?
    val signingPassword = project.properties["signing.password"] as String?

    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["maven"])
}