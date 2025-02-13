package com.jetbrains.oniguruma

import kotlin.Char.Companion.MIN_HIGH_SURROGATE
import kotlin.Char.Companion.MIN_LOW_SURROGATE
import kotlin.test.Test
import kotlin.test.assertEquals

typealias CharOffset = Int

class OnigurumaTest {

    @Test
    fun matching() {
        withMatcher("[0-9]+") { matcher ->
            assertEquals(
                listOf(Capture(0, 2)),
                matcher.match("12:00pm", 0)
            )
        }
    }

    @Test
    fun matchingFromPosition() {
        withMatcher("[0-9]+") { matcher ->
            assertEquals(
                listOf(Capture(3, 5)),
                matcher.match("12:00pm", 2)
            )
        }
    }

    @Test
    fun matchingWithGroups() {
        withMatcher("([0-9]+):([0-9]+)") { matcher ->
            assertEquals(
                listOf(Capture(0, 5), Capture(0, 2), Capture(3, 5)),
                matcher.match("12:00pm", 0)
            )
        }
    }

    @Test
    fun matchBeginPosition() {
        withMatcher("\\Gbar") { matcher ->
            val noBeginMatch = matcher.match(
                "foo bar",
                4,
                matchBeginPosition = false,
                matchBeginString = true,
            )
            assertEquals(emptyList(), noBeginMatch)

            val beginMatch = matcher.match(
                "foo bar",
                4,
                matchBeginPosition = true,
                matchBeginString = true,
            )
            assertEquals(listOf(Capture(start = 4, end = 7)), beginMatch)
        }
    }

    @Test
    fun matchBeginString() {
        withMatcher("\\Afoo") { matcher ->
            val noBeginMatch = matcher.match(
                "foo bar",
                0,
                matchBeginPosition = true,
                matchBeginString = false,
            )
            assertEquals(emptyList(), noBeginMatch)

            val beginMatch = matcher.match(
                "foo bar",
                0,
                matchBeginPosition = true,
                matchBeginString = true,
            )
            assertEquals(listOf(Capture(start = 0, end = 3)), beginMatch)
        }
    }


    @Test
    fun cyrillicMatchingSinceIndex() {
        withMatcher("мир") { matcher ->
            assertEquals(
                listOf(Capture(21, 24)),
                matcher.match("привет, мир; привет, мир!", 9)
            )
        }
    }

    @Test
    fun cyrillicMatching() {
        withMatcher("мир") { matcher ->
            assertEquals(
                listOf(Capture(8, 11)),
                matcher.match("привет, мир!", 0)
            )
        }
    }

    @Test
    fun unicodeMatching() {
        withMatcher("мир") { matcher ->
            val string = "\uD83D\uDEA7\uD83D\uDEA7\uD83D\uDEA7 привет, мир 123!"
            val match = matcher.match(string, 0)
            assertEquals("мир", string.substring(match.first().start, match.first().end))
        }
    }

    private fun withMatcher(s: String, block: (Matcher) -> Unit) {
        val oniguruma = Oniguruma.instance
        val regex = oniguruma.createRegex(s.encodeToByteArray())
        try {
            block(Matcher(oniguruma, regex))
        } finally {
            oniguruma.freeRegex(regex)
        }
    }

    class Matcher(private val oniguruma: Oniguruma, private val regexPtr: Long) {
        fun match(
            string: String,
            startCharOffset: Int,
            matchBeginPosition: Boolean = true,
            matchBeginString: Boolean = true
        ): List<Capture> {
            val stringBytes = string.encodeToByteArray()
            return oniguruma.match(
                regexPtr,
                stringBytes,
                byteOffsetByCharOffset(string, startCharOffset),
                matchBeginString = matchBeginString,
                matchBeginPosition = matchBeginPosition
            )?.asSequence()?.windowed(size = 2, step = 2, partialWindows = false) { (first, second) ->
                val start = stringBytes.decodeToString(0, first).length
                val end = start + stringBytes.decodeToString(first, second).length
                Capture(start, end)
            }?.toList() ?: emptyList()
        }
    }

    data class Capture(val start: CharOffset, val end: CharOffset)

    companion object {
        private fun byteOffsetByCharOffset(
            charSequence: CharSequence,
            charOffset: Int,
        ): Int {
            if (charOffset <= 0) {
                return 0
            }
            var result = 0
            var i = 0
            while (i < charOffset) {
                val char = charSequence[i]
                if (char.isHighSurrogate() && i + 1 < charSequence.length && charSequence[i + 1].isLowSurrogate()) {
                    result += utf8Size(codePoint(char, charSequence[i + 1]))
                    i++ // Skip the low surrogate
                } else {
                    result += utf8Size(char.code)
                }
                i++
            }
            return result
        }

        private fun utf8Size(codePoint: Int): Int {
            return when {
                codePoint <= 0x7F -> 1 // 1 byte for ASCII
                codePoint <= 0x7FF -> 2 // 2 bytes for U+0080 to U+07FF
                codePoint <= 0xFFFF -> 3 // 3 bytes for U+0800 to U+FFFF
                else -> 4
            }
        }

        private fun codePoint(high: Char, low: Char): Int {
            return (((high - MIN_HIGH_SURROGATE) shl 10) or (low - MIN_LOW_SURROGATE)) + 0x10000
        }
    }
}