plugins {
    kotlin("jvm") version "2.1.10"
    id("me.champeau.jmh") version "0.6.8"
}

group = "com.jetbrains.oniguruma"
version = "1.0-SNAPSHOT"

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