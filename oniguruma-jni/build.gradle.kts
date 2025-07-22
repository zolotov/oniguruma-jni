import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.desktop.buildscripts.*
import org.jetbrains.desktop.buildscripts.normalizedName

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jmh)
    id("com.vanniktech.maven.publish") version "0.33.0"
    id("org.jetbrains.dokka") version "2.0.0"
    id("org.hildan.github.changelog") version "2.2.0"
    id("ru.vyarus.github-info") version "2.0.0"
}

group = "me.zolotov.oniguruma"
description = """
    A JNI wrapper for the Oniguruma regular expression library, with Rust implementation using the onig crate.
    This library is primarily designed to support syntax highlighting in IntelliJ-based IDEs through the textmate-core library.
""".trimIndent()

github {
    user = "zolotov"
    license = "Apache"
}

changelog {
    githubUser = github.user
    futureVersionTag = project.version.toString()
}

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
            into("native/${platform.normalizedName}")
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

val slimJar = tasks.register<Jar>("slimJar") {
    group = "build"
    description = "Assembles a jar archive without native libraries"

    archiveClassifier.set("slim")
    from(sourceSets.main.get().output.classesDirs)

    from(sourceSets.main.get().output.resourcesDir!!) {
        exclude("**/native")
    }

    manifest {
        from(tasks.jar.get().manifest)
    }
    dependsOn(tasks.processResources)
}

val PACKAGING_ATTRIBUTE = Attribute.of("me.zolotov.oniguruma.packaging", String::class.java)

configurations {
    apiElements {
        attributes {
            attribute(PACKAGING_ATTRIBUTE, "full")
        }
    }

    runtimeElements {
        attributes {
            attribute(PACKAGING_ATTRIBUTE, "full")
        }
    }
}

val javaComponent = components.findByName("java") as AdhocComponentWithVariants
javaComponent.addVariantsFromConfiguration(configurations.consumable("slim") {
    attributes {
        attribute(PACKAGING_ATTRIBUTE, "slim")
    }
    outgoing { artifact(slimJar) }
}.get()) {}

compileRustBindingsTaskByPlatform.filterValues { it.get().isEnabled }.forEach { (platform, task) ->
    val conf = configurations.consumable("bindings_${platform.normalizedName}") {
        attributes {
            attribute(Attribute.of("me.zolotov.oniguruma.platform", String::class.java), platform.normalizedName)
        }
        outgoing {
            artifact(task.map { it.libraryFile }) {
                classifier = platform.normalizedName
            }
        }
    }.get()
    javaComponent.addVariantsFromConfiguration(conf) { }
}

mavenPublishing {
    configure(KotlinJvm(
        javadocJar = JavadocJar.Dokka("dokkaHtml"),
        sourcesJar = true
    ))
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/zolotov/oniguruma-jni")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("zolotov")
                name.set("Alexander Zolotov")
                email.set("goldifit@gmail.com")
                url.set("https://github.com/zolotov/")
            }
        }

        scm {
            url.set("https://github.com/zolotov/oniguruma-jni")
            connection.set("scm:git:git://github.com/zolotov/oniguruma-jni.git")
            developerConnection.set("scm:git:ssh://github.com/zolotov/oniguruma-jni.git")
        }
    }
}