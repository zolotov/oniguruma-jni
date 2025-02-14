package me.zolotov.oniguruma

import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.Volatile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.outputStream

internal object OnigurumaLoader {
    @Volatile
    private var loaded = false

    @Synchronized
    fun loadFromResources() {
        if (!loaded) {
            val libraryName = System.mapLibraryName("rust_oniguruma_bindings")
            val resourcePath = determineResourcePath(libraryName)
            val extractedLib = extractLibraryToTemporaryDirectory(resourcePath, libraryName)
            System.load(extractedLib.absolutePathString())

            loaded = true
        }
    }

    @Synchronized
    fun loadFromFile(path: Path) {
        if (!loaded) {
            System.load(path.absolutePathString())
            loaded = true
        }
    }

    private fun determineResourcePath(libraryName: String): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osName = when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("linux") -> "linux"

            else -> throw UnsupportedOperationException("Unsupported operating system: $os")
        }
        val archName = when {
            arch.contains("amd64") || arch.contains("x86_64") -> "x86_64"
            arch.contains("aarch64") || arch.contains("arm64") -> "aarch64"
            arch.contains("x86") || arch.contains("i386") -> "x86"
            arch.contains("arm") -> {
                val bits = System.getProperty("sun.arch.data.model")
                "arm${if (bits == "32") "32" else "64"}"
            }

            else -> throw UnsupportedOperationException("Unsupported architecture: $arch")
        }
        return "/native/${osName}-${archName}/$libraryName"
    }

    @OptIn(ExperimentalPathApi::class)
    private fun extractLibraryToTemporaryDirectory(resourcePath: String, libraryName: String): Path {
        val tempDirectory = Files.createTempDirectory("oniguruma-jni")
        val libraryFile = tempDirectory.resolve(libraryName)

        javaClass.getResourceAsStream(resourcePath)?.use { input ->
            libraryFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw UnsatisfiedLinkError("Native library not found in resources: $resourcePath")

        Runtime.getRuntime().addShutdownHook(Thread {
            tempDirectory.deleteRecursively()
        })

        return libraryFile
    }
}