package me.zolotov.oniguruma.benchmark;

import me.zolotov.oniguruma.Oniguruma;
import me.zolotov.oniguruma.ffi.OnigurumaFFI;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class CreateBenchmark {

    private static final byte[] PATTERN = "[0-9]+".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] TEXT    =
        "\uD83D\uDEA7\uD83D\uDEA7\uD83D\uDEA7 привет, мир 123!"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private Oniguruma    jni;
    private OnigurumaFFI ffi;

    @Setup(Level.Trial)
    public void setup() {
        jni = Oniguruma.Companion.createFromResources();
        ffi = new OnigurumaFFI();
    }

    // ── JNI: createString ──────────────────────────────────────────────────

    @Benchmark
    public void jni_createString(Blackhole bh) {
        long ptr = jni.createString(TEXT);
        bh.consume(ptr);
        jni.freeString(ptr);
    }

    // ── FFI: createString ──────────────────────────────────────────────────

    @Benchmark
    public void ffi_createString(Blackhole bh) {
        long ptr = ffi.createString(TEXT);
        bh.consume(ptr);
        ffi.freeString(ptr);
    }

    // ── JNI: createRegex ──────────────────────────────────────────────────

    @Benchmark
    public void jni_createRegex(Blackhole bh) {
        long ptr = jni.createRegex(PATTERN);
        bh.consume(ptr);
        jni.freeRegex(ptr);
    }

    // ── FFI: createRegex ──────────────────────────────────────────────────

    @Benchmark
    public void ffi_createRegex(Blackhole bh) {
        long ptr = ffi.createRegex(PATTERN);
        bh.consume(ptr);
        ffi.freeRegex(ptr);
    }
}
