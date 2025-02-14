package com.jetbrains.oniguruma

import me.zolotov.oniguruma.Oniguruma
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
open class OnigurumaBenchmark {
    lateinit var oniguruma: Oniguruma
    var stringPtr: Long = 0
    var regexPtr: Long = 0

    @Setup(Level.Trial)
    fun setup() {
        oniguruma = Oniguruma.createFromResources()
        regexPtr = oniguruma.createRegex("[0-9]+".encodeToByteArray())
        stringPtr = oniguruma.createString("\uD83D\uDEA7\uD83D\uDEA7\uD83D\uDEA7 привет, мир 123!".encodeToByteArray())
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
}