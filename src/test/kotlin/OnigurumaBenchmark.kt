package com.jetbrains.oniguruma

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
open class OnigurumaBenchmark {
    lateinit var oniguruma: Oniguruma
    lateinit var string: ByteArray
    var regexPtr: Long = 0

    @Setup(Level.Trial)
    fun setup() {
        oniguruma = Oniguruma.instance;
        regexPtr = oniguruma.createRegex("[0-9]+".encodeToByteArray())
        string = "\uD83D\uDEA7\uD83D\uDEA7\uD83D\uDEA7 привет, мир 123!".encodeToByteArray()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        oniguruma.freeRegex(regexPtr);
    }

    @Benchmark
    fun benchmarkMatch() {
        oniguruma.match(
            regexPtr = regexPtr,
            text = string,
            byteOffset = 0,
            matchBeginPosition = true,
            matchBeginString = true
        );
    }
}