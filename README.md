# Oniguruma JNI

A JNI wrapper for the Oniguruma regular expression library, with Rust implementation using the [onig](https://crates.io/crates/onig) crate.
This library is primarily designed to support syntax highlighting in [IntelliJ](https://www.jetbrains.com/idea/)-based IDEs through the [`textmate-core`](https://github.com/JetBrains/intellij-community/tree/master/plugins/textmate/core) library.

## Overview

This project provides Java Native Interface (JNI) bindings for TextMate grammar pattern matching,
implemented in Rust using the `onig` crate which provides the native bindings to the Oniguruma regular expression library.

## Installation

Add the following dependency to your project:

```kotlin
dependencies {
    implementation("com.github.zolotov:oniguruma-jni:0.1")
}
```

## Usage

### Basic Setup

```kotlin
// Load the library from bundled resources
// Note: This method has performance overhead during instantiation (unpacking library from jar)
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
    - JDK 8 or later
    - Rust toolchain
    - Cross tool installed: `cargo install cross`
3. Build the project using Gradle:
   ```bash
   ./gradlew build
   ```

## Contributing

Contributions are welcome! Please feel free to submit pull requests.

## Acknowledgments

- Oniguruma library developers
- onig-rs crate maintainers

## Note

This library is primarily intended for use with the `textmate-core` library in IntelliJ-based IDEs. While it can be used independently, the API is designed with this specific use case in mind.