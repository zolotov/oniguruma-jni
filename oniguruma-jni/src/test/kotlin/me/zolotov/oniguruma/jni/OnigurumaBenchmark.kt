package me.zolotov.oniguruma.jni
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
open class OnigurumaBenchmark {
    lateinit var oniguruma: Oniguruma
    lateinit var smallText: ByteArray
    lateinit var largeText: ByteArray
    lateinit var invalidPattern: ByteArray
    var stringPtr: Long = 0
    var regexPtr: Long = 0

    @Setup(Level.Trial)
    fun setup() {
        oniguruma = Oniguruma.createFromResources()
        regexPtr = oniguruma.createRegex("[0-9]+".encodeToByteArray())
        smallText = "🚧🚧🚧 привет, мир 123!".encodeToByteArray()
        largeText = buildString {
            while (length < 64 * 1024) {
                append("val variable = listOf(1, 2, 3).map { it * it } // a typical line of source code\n")
            }
        }.encodeToByteArray()
        invalidPattern = "(unclosed[".encodeToByteArray()
        stringPtr = oniguruma.createString(smallText)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        oniguruma.freeRegex(regexPtr)
        oniguruma.freeString(stringPtr)
    }

    @Benchmark
    fun benchmarkMatch() {
        oniguruma.match(
            regexPtr = regexPtr,
            textPtr = stringPtr,
            byteOffset = 0,
            matchBeginPosition = true,
            matchBeginString = true
        )
    }

    @Benchmark
    fun benchmarkCreateString(): Long {
        val ptr = oniguruma.createString(smallText)
        oniguruma.freeString(ptr)
        return ptr
    }

    @Benchmark
    fun benchmarkCreateStringLarge(): Long {
        val ptr = oniguruma.createString(largeText)
        oniguruma.freeString(ptr)
        return ptr
    }

    @Benchmark
    fun benchmarkCreateRegexError(): Long {
        return try {
            oniguruma.createRegex(invalidPattern)
        } catch (e: RuntimeException) {
            0
        }
    }
}
