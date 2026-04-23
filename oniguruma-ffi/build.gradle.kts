plugins {
    java
    alias(libs.plugins.jmh)
}

group = "me.zolotov.oniguruma"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

val nativeFFILib = layout.buildDirectory.file("native/liboniguruma_ffi.so")

val compileNativeFFI = tasks.register<Exec>("compileNativeFFI") {
    val srcFile  = rootProject.file("native-ffi/oniguruma_ffi.c")
    val outFile  = nativeFFILib.get().asFile
    inputs.file(srcFile)
    outputs.file(outFile)
    doFirst { outFile.parentFile.mkdirs() }
    commandLine(
        "gcc", "-shared", "-fPIC", "-O2",
        "-o", outFile.absolutePath,
        srcFile.absolutePath,
        "-lonig"
    )
}

dependencies {
    implementation(project(":oniguruma-jni"))
    jmhImplementation(project(":oniguruma-jni"))
    jmhImplementation("org.openjdk.jmh:jmh-core:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.named("compileJava") {
    dependsOn(compileNativeFFI)
}

tasks.named("compileJmhJava") {
    dependsOn(compileNativeFFI)
}

jmh {
    warmupIterations  = 1
    iterations        = 3
    fork              = 1
    failOnError       = true
    resultFormat      = "JSON"
    resultsFile       = rootProject.file("benchmark-results/results.json")
    jvmArgsAppend.addAll(
        "--enable-native-access=ALL-UNNAMED",
        "-Doniguruma.ffi.lib=${nativeFFILib.get().asFile.absolutePath}"
    )
}
