package org.jetbrains.desktop.buildscripts

import org.gradle.api.tasks.Input
import org.gradle.internal.impldep.kotlinx.serialization.Serializable

@Serializable
data class Platform(
    @get:Input val os: Os,
    @get:Input val arch: Arch,
)

val Platform.normalizedName get(): String = "${os.normalizedName}-${arch.normalizedName}"

enum class Os(val normalizedName: String) {
    LINUX("linux"), MACOS("macos"), WINDOWS("windows");
}

enum class Arch(val normalizedName: String) {
    aarch64("aarch64"), x86_64("x86_64")
}

fun currentOs(): Os  {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") -> Os.WINDOWS
        os.contains("mac") -> Os.MACOS
        os.contains("nux") || os.contains("nix") || os.contains("aix") -> Os.LINUX
        else -> error("unsupported os '$os'")
    }
}

fun currentArch(): Arch = when (val arch = System.getProperty("os.arch").lowercase()) {
    "x86_64", "amd64", "x64" -> Arch.x86_64
    "arm64", "aarch64" -> Arch.aarch64
    else -> error("unsupported arch '$arch'")
}

fun currentPlatform(): Platform = Platform(currentOs(), currentArch())