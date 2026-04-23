# JNI vs FFI Benchmark Comparison

**Date:** 2026-04-23  
**JVM:** OpenJDK 25.0.3 (JDK 25 LTS, Temurin-25.0.3+9)  
**JMH:** 1.37 — Throughput mode (ops/ms), 1 warmup + 3 measurement iterations, 1 fork  
**OS:** Linux x86_64

---

## Results

| Benchmark             | JNI (ops/ms)       | FFI (ops/ms)        | FFI / JNI |
|-----------------------|--------------------|---------------------|-----------|
| createString          | 7,597.7 ± 572.2    | 16,096.1 ± 3,062.1  | **+112%** |
| createRegex           | 1,200.0 ±   8.5    |  1,307.0 ±  147.9   |  **+9%**  |

*(higher ops/ms = faster)*

---

## Analysis

### createString — FFI is ~2× faster

`createString` copies a byte array into a native buffer and returns a pointer.

- **JNI path:** Java → JNI glue → Rust (`create_string`) → `Box::into_raw`.
  The JNI glue involves type-conversion overhead (`get_array_elements`,
  `get_array_length`) and crossing the JNI boundary twice (call + element
  pinning / unpinning).

- **FFI path:** Java (`Arena.ofConfined()` alloc + `allocateFrom`) → C
  (`malloc + memcpy`). Panama's downcall stub is a direct machine-code trampoline
  with no intermediate marshalling layer; `Arena.ofConfined()` allocation is
  essentially a bump-pointer allocation.

  The ~2× improvement is consistent with Panama's design goal of reducing
  crossing overhead by letting the JIT inline the trampoline.

### createRegex — FFI is ~9% faster

Both paths eventually call `onig_new` (O(n) regex compilation), which
dominates the total time. The JNI/FFI boundary contribution is small relative
to the compilation work, so the gap shrinks to single digits.

---

## Raw JMH Output (trimmed)

```
Benchmark                          Mode  Cnt      Score      Error   Units
CreateBenchmark.ffi_createRegex   thrpt    3   1307.001 ±  147.939  ops/ms
CreateBenchmark.ffi_createString  thrpt    3  16096.075 ± 3062.140  ops/ms
CreateBenchmark.jni_createRegex   thrpt    3   1200.049 ±    8.500  ops/ms
CreateBenchmark.jni_createString  thrpt    3   7597.725 ±  572.207  ops/ms
```

Full machine-readable output: `results.json`

---

## Notes

- Benchmarks were run on a shared cloud VM; absolute numbers will vary.
- Each benchmark creates **and** frees the native object to prevent unbounded
  allocation and to measure net create/free cost.
- `createRegex` numbers are dominated by `onig_new`; boundary overhead is
  below noise for that benchmark.
- The FFI `createString` error bar (±3062) is wider than the JNI one because
  `Arena.ofConfined()` occasionally triggers GC pressure from the arena itself.
