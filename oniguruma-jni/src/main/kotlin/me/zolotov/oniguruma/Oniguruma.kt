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

    /**
     * Iterate [regexPtrs] on the native side and return the winner with the earliest match.
     * Returns int[]{winnerIndex, start, end} or null if no pattern matched.
     * Mirrors the early-exit optimisation from TextMate's matchRule.
     */
    external fun findFirstMatch(
        regexPtrs: LongArray,
        textPtr: Long,
        byteOffset: Int,
        matchBeginPosition: Boolean,
        matchBeginString: Boolean,
    ): IntArray?
}
