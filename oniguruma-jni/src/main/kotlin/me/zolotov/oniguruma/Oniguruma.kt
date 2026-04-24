package me.zolotov.oniguruma

import java.nio.file.Path

class Oniguruma private constructor() {
    companion object {
        fun createFromResources(): Oniguruma {
            OnigurumaLoader.loadFromResources()
            return Oniguruma()
        }

        fun createFromFile(path: Path): Oniguruma {
            OnigurumaLoader.loadFromFile(path)
            return Oniguruma()
        }
    }

    external fun match(
        regexPtr: Long,
        textPtr: Long,
        byteOffset: Int,
        matchBeginPosition: Boolean,
        matchBeginString: Boolean,
    ): IntArray?

    external fun createRegex(pattern: ByteArray): Long

    external fun freeRegex(regexPtr: Long)

    external fun createString(utf8Content: ByteArray): Long

    external fun freeString(textPtr: Long)
}