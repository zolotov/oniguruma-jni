# Oniguruma JNI

[![Maven central version](https://img.shields.io/maven-central/v/me.zolotov.oniguruma/oniguruma-jni.svg)](https://search.maven.org/artifact/me.zolotov.oniguruma/oniguruma-jni)
[![GitHub Actions Workflow Status](https://img.shields.io/github/actions/workflow/status/zolotov/oniguruma-jni/build.yaml)](https://github.com/zolotov/oniguruma-jni/actions/workflows/build.yaml)
[![GitHub License](https://img.shields.io/github/license/zolotov/oniguruma-jni)](https://github.com/zolotov/oniguruma-jni/blob/main/LICENSE)

A JNI wrapper for the Oniguruma regular expression library, with Rust implementation using the [onig](https://crates.io/crates/onig) crate.
This library is primarily designed to support syntax highlighting in [IntelliJ](https://www.jetbrains.com/idea/)-based IDEs through the [`textmate-core`](https://github.com/JetBrains/intellij-community/tree/master/plugins/textmate/core) library.

## Overview

This project provides Java Native Interface (JNI) bindings for TextMate grammar pattern matching,
implemented in Rust using the `onig` crate which provides the native bindings to the Oniguruma regular expression library.

## Installation

Add the following dependency to your project:

```kotlin
dependencies {
    implementation("me.zolotov.oniguruma:oniguruma-jni:$version")
}
```

By default, the dependency resolves to the "fat" jar that bundles native libraries for all
supported platforms as jar resources, ready to be loaded with `Oniguruma.createFromResources()`.

### Slim jar

If you don't want the native libraries of all platforms on your classpath (e.g. you ship
platform-specific distributions or manage the native library yourself), request the "slim" jar
instead by setting the `me.zolotov.oniguruma.packaging` attribute on the dependency:

```kotlin
dependencies {
    implementation("me.zolotov.oniguruma:oniguruma-jni:$version") {
        attributes {
            attribute(Attribute.of("me.zolotov.oniguruma.packaging", String::class.java), "slim")
        }
    }
}
```

The slim jar contains no native libraries, so load the library from a file with
`Oniguruma.createFromFile(path)`. The per-platform native libraries are published alongside the
jars and can be resolved with the `me.zolotov.oniguruma.platform` attribute
(`<os>-<arch>`, e.g. `macos-aarch64`, `linux-x86_64`, `windows-x86_64`):

```kotlin
val onigurumaNativeBinding: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Attribute.of("me.zolotov.oniguruma.platform", String::class.java), "macos-aarch64")
    }
}

dependencies {
    onigurumaNativeBinding("me.zolotov.oniguruma:oniguruma-jni:$version")
}
```

## Usage

### Basic Setup

```kotlin
// Load the library from bundled resources
// Note: This method has performance overhead during instantiation (unpacking a native part from jar)
// and JVM shutdown (cleanup hook to remove the unpacked file)
val oniguruma = Oniguruma.createFromResources()

// Or load from a specific file path (preferred for better performance)
val oniguruma = Oniguruma.createFromFile(Path.of("/path/to/library"))
```

### Pattern Matching

```kotlin
val oniguruma = Oniguruma.createFromResources()

// Create pattern and string handles
val pattern = "pattern".toByteArray()
val text = "text to match".toByteArray()

val textPtr = oniguruma.createString(text)
try {
    val regexPtr = oniguruma.createRegex(pattern)
    try {
        val result = oniguruma.match(
            regexPtr = regexPtr,
            textPtr = textPtr,
            byteOffset = 0,
            matchBeginPosition = true,
            matchBeginString = false
        )

        // Process results
        result?.let {
            // Match found, process the integer array of positions
            it.asSequence()?.windowed(size = 2, step = 2, partialWindows = false) { (startByteOffset, endByteOffset) ->

            }
        }
    } finally {
        // Clean up native regex
        oniguruma.freeRegex(regexPtr)
    }
} finally {
    // Clean up native string
    oniguruma.freeString(textPtr)
}
```

## Building from Source

1. Clone the repository
2. Ensure you have the following prerequisites:
    - JDK 17 or later
    - Rust toolchain
3. Build the project using Gradle:
   ```bash
   ./gradlew :oniguruma-jni:build
   ```

## Contributing

Contributions are welcome! Please feel free to submit pull requests.

## Acknowledgments

- Oniguruma library developers
- onig-rs crate maintainers

## Note

This library is primarily intended for use with the `textmate-core` library in IntelliJ-based IDEs. While it can be used independently, the API is designed with this specific use case in mind.
