package com.jetbrains.oniguruma

import me.zolotov.oniguruma.Oniguruma
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
open class MultiPatternBenchmark {

    @Param("1", "2", "4", "8", "16", "32", "64", "128")
    var patternCount: Int = 1

    lateinit var oniguruma: Oniguruma
    var stringPtr: Long = 0
    lateinit var regexPtrs: LongArray

    @Setup(Level.Trial)
    fun setup() {
        oniguruma = Oniguruma.createFromResources()
        stringPtr = oniguruma.createString(
            "Hello World 123, test! More text... 456 done!".encodeToByteArray()
        )
        regexPtrs = LongArray(patternCount) { i ->
            oniguruma.createRegex(BASE_PATTERNS[i % BASE_PATTERNS.size].encodeToByteArray())
        }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        regexPtrs.forEach { oniguruma.freeRegex(it) }
        oniguruma.freeString(stringPtr)
    }

    /**
     * Kotlin-side iteration: call match() once per pattern and track the earliest start position.
     * Returns the match data (IntArray of [start, end, ...groups]) of the winning pattern.
     */
    @Benchmark
    fun kotlinIteration(): IntArray? {
        var bestStart = Int.MAX_VALUE
        var bestMatch: IntArray? = null
        for (regexPtr in regexPtrs) {
            val match = oniguruma.match(regexPtr, stringPtr, 0, true, true)
            if (match != null && match[0] < bestStart) {
                bestStart = match[0]
                bestMatch = match
            }
        }
        return bestMatch
    }

    /**
     * Native-side iteration: pass all regex pointers to Rust in a single JNI call.
     * Returns [patternIndex, start, end, ...groups] of the winning pattern, or null if no match.
     */
    @Benchmark
    fun nativeIteration(): IntArray? {
        return oniguruma.matchEarliest(regexPtrs, stringPtr, 0, true, true)
    }

    companion object {
        private val BASE_PATTERNS = listOf(
            "\\d+",         // matches "123" at ~12
            "[a-z]+",       // matches "ello" at 1
            "[A-Z][a-z]*",  // matches "Hello" at 0
            ",",            // matches "," at 15
            "\\s+",         // matches " " at 5
            "!",            // matches "!" at 21
            "[A-Z]+",       // matches "H" at 0
            "\\w+",         // matches "Hello" at 0
            "\\.",          // matches "." at 33
            "[0-9]{2,}",    // matches "123" at 12
            "test",         // matches "test" at 17
            "Hello",        // matches "Hello" at 0
        )
    }
}
