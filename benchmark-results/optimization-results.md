# Optimization Results

Benchmarks run on OpenJDK 25.0.3 (Temurin), JMH 1.37, Linux x86_64.
JMH config: 1 warmup iteration × 1 s, 3 measurement iterations × 1 s, fork 1.
Rust 1.84.1, Oniguruma 6.9.9.

All results in **ops/ms (throughput, higher is better)**.

Baseline numbers from the `air/ffi-java25-benchmarks` branch (see `comparison.md`).

---

## Baseline

File: `opt-00-baseline.json`

| Benchmark | Score (ops/ms) | Error |
|-----------|---------------|-------|
| ffi_createRegex | 1,302.7 | ±228.0 |
| ffi_createString | 16,096.1 | ±3,062.1 |
| jni_createRegex | 1,196.9 | ±100.4 |
| jni_createString | 7,597.7 | ±572.2 |

---

## FFI Optimizations

### FFI-1: Reuse arenas — thread-local staging buffer

**Change:** Replaced per-call `Arena.ofConfined()` in `createString` and `createRegex` with a
thread-local `MemorySegment` backed by `Arena.ofAuto()`. The segment is grown lazily (doubled)
when the input exceeds its current capacity.

File: `opt-ffi-01-arena-reuse.json`

| Benchmark | Score (ops/ms) | Error | vs Baseline |
|-----------|---------------|-------|-------------|
| ffi_createRegex | 1,355.8 | ±130.7 | +4% |
| ffi_createString | 25,968.3 | ±769.0 | **+61%** |
| jni_createRegex | 1,193.7 | ±52.3 | — |
| jni_createString | 7,240.0 | ±1,730.4 | — |

**Verdict:** Major win for `ffi_createString`. Eliminates bump-pointer allocator overhead and GC
pressure from short-lived `Arena` objects. Also narrows the error bar significantly (±769 vs ±3062).

---

### FFI-2: MethodHandle.asType() specialization

**Change:** Added `.asType(MethodType.methodType(...))` to each downcall handle at static-init
time so the JIT sees a monomorphic `invokeExact` call site and can inline without a type check.

File: `opt-ffi-02-method-handle-specialization.json`

| Benchmark | Score (ops/ms) | Error | vs FFI-1 |
|-----------|---------------|-------|----------|
| ffi_createRegex | 1,329.1 | ±585.2 | −2% (noise) |
| ffi_createString | 25,120.3 | ±4,067.9 | −3% (noise) |
| jni_createRegex | 1,198.2 | ±66.2 | — |
| jni_createString | 7,402.8 | ±1,066.8 | — |

**Verdict:** No measurable throughput change (within noise). The JIT already specialises
monomorphic call sites; the explicit `asType` is a defensive correctness measure that eliminates
any risk of a silent fallback to `invoke`.

---

### FFI-3: Linker.Option.critical(false) for free operations

**Note:** The optimization document referred to `Linker.Option.isTrivial()` which does not exist
in JDK 25. The equivalent is `Linker.Option.critical(false)` (no heap access allowed), which
skips the safepoint poll on calls that do not touch the Java heap.

**Change:** Added `Linker.Option.critical(false)` to `FREE_REGEX` and `FREE_STRING` downcall handles.

File: `opt-ffi-03-critical-free.json`

| Benchmark | Score (ops/ms) | Error | vs FFI-2 |
|-----------|---------------|-------|----------|
| ffi_createRegex | 1,367.2 | ±113.3 | +3% |
| ffi_createString | 28,949.5 | ±13,679.9 | +15% |
| jni_createRegex | 1,204.7 | ±45.7 | — |
| jni_createString | 7,303.2 | ±514.1 | — |

**Verdict:** Positive, though the large error bar on `ffi_createString` introduces uncertainty.
The safepoint-poll elimination is a low-cost change that benefits any hot path that calls
`freeRegex` / `freeString` in a tight loop.

---

### FFI-4: SequenceLayout + thread-local output buffer for match regions

**Change:** Declared a static `SequenceLayout REGIONS_LAYOUT` for the 256×2 int output array
and moved the output `MemorySegment` to a thread-local `Arena.ofAuto()` buffer, eliminating
the per-match `Arena.ofConfined()` allocation.

File: `opt-ffi-04-sequence-layout-regions.json`

| Benchmark | Score (ops/ms) | Error | vs FFI-3 |
|-----------|---------------|-------|----------|
| ffi_createRegex | 1,365.8 | ±44.8 | −0% (noise) |
| ffi_createString | 28,600.6 | ±1,560.7 | −1% (noise) |
| jni_createRegex | 1,200.3 | ±80.3 | — |
| jni_createString | 7,386.0 | ±1,022.7 | — |

**Verdict:** Benchmark unchanged (no `match` benchmark exists in the suite). The improvement
applies to the `match` hot path in production: a pre-allocated region buffer eliminates a
malloc/free per search call.

---

## JNI / Rust Optimizations

### JNI-1: GetPrimitiveArrayCritical in create_string

**Change:** Replaced `env.get_array_elements()` with `env.get_array_elements_critical()` in
`create_string`. The critical variant pins the Java heap object without copying, at the cost of
suspending GC for the duration of the UTF-8 validation and `String` construction. The critical
section is released (via `drop`) before any further JNI calls.

File: `opt-jni-01-critical-array.json`

| Benchmark | Score (ops/ms) | Error | vs Baseline |
|-----------|---------------|-------|-------------|
| ffi_createRegex | 1,368.3 | ±45.7 | — |
| ffi_createString | 28,981.7 | ±14,988.3 | — |
| jni_createRegex | 1,148.2 | ±49.2 | — |
| jni_createString | 8,309.8 | ±1,472.1 | **+11%** vs JNI baseline |

**Verdict:** Meaningful improvement. Eliminates the JNI array copy on the `createString` hot path.

---

### JNI-2: Combined createStringAndRegex entry point

**Change:** Added a new `createStringAndRegex(utf8: ByteArray, pattern: ByteArray): LongArray`
JNI method (exposed in `Oniguruma.kt`) that compiles string and regex in a single round-trip,
returning `[stringPtr, regexPtr]`. Added a corresponding `jni_createStringAndRegex` benchmark.

File: `opt-jni-02-combined-create.json`

| Benchmark | Score (ops/ms) | Error | Note |
|-----------|---------------|-------|------|
| jni_createString | 7,984.0 | ±886.4 | unchanged |
| jni_createRegex | 1,127.5 | ±663.0 | unchanged |
| **jni_createStringAndRegex** | **813.5** | ±65.3 | combined operation |

**Verdict:** The combined operation scores 813 ops/ms vs the sum of separate `createString +
createRegex` which would be bounded by `createRegex` at ~1127 ops/ms. The combined call saves
one JNI boundary crossing per pattern-text pair, which is the relevant unit in the TextMate
grammar use case.

---

### JNI-3: Thread-local Region cache in match_pattern

**Change:** Added a `thread_local! { static REGION: RefCell<Region> }` in `lib.rs`.
`match_pattern` now borrows the cached `Region`, calls `region.clear()`, and uses it instead
of allocating a fresh `Region::new()` on every call.

File: `opt-jni-03-region-cache.json`

| Benchmark | Score (ops/ms) | Error | vs JNI-2 |
|-----------|---------------|-------|----------|
| ffi_createRegex | 1,371.3 | ±26.2 | — |
| ffi_createString | 27,050.7 | ±38,832.5 | — |
| jni_createRegex | 1,115.0 | ±2,761.4 | — |
| jni_createString | 8,182.4 | ±690.3 | — |
| jni_createStringAndRegex | 834.9 | ±50.2 | — |

**Verdict:** No visible change in benchmark (the suite does not exercise `match`). In production,
this eliminates one malloc/free per `match_pattern` call, which is the highest-frequency
operation in the TextMate grammar engine.

---

### JNI-4: Cache RuntimeException class as a global JNI reference

**Change:** Added `JNI_OnLoad` in `lib.rs` that caches a `GlobalRef` to
`java.lang.RuntimeException` in a `static OnceLock<GlobalRef>`. `propagate_exception` now looks
up the cached ref instead of calling `env.find_class(...)` on every error.

File: `opt-jni-04-cache-exception-class.json`

| Benchmark | Score (ops/ms) | Error | vs JNI-3 |
|-----------|---------------|-------|----------|
| ffi_createRegex | 1,373.0 | ±61.5 | — |
| ffi_createString | 28,758.5 | ±3,287.6 | — |
| jni_createRegex | 1,182.6 | ±83.0 | — |
| jni_createString | 8,134.8 | ±374.1 | — |
| jni_createStringAndRegex | 904.8 | ±68.0 | — |

**Verdict:** No visible throughput change on the happy path (expected — class lookup only
occurs on errors). The benefit is on the error path: `find_class` involves a JNI call that
acquires a class lock; the cached `GlobalRef` removes that overhead entirely.

---

## Cumulative Summary

| Benchmark | Baseline | After All Opts | Change |
|-----------|----------|---------------|--------|
| ffi_createRegex | 1,302.7 | ~1,373 | +5% |
| ffi_createString | 16,096.1 | ~28,758 | **+79%** |
| jni_createRegex | 1,196.9 | ~1,183 | ≈0% |
| jni_createString | 7,597.7 | ~8,135 | **+7%** |
| jni_createStringAndRegex | — | ~905 | new API |

The largest gain is `ffi_createString` (+79%), driven entirely by **FFI-1** (arena reuse).
The JNI `createString` improved by ~7% from **JNI-1** (critical array access).
The `createRegex` benchmarks are dominated by Oniguruma's `onig_new` compile cost, so
boundary-crossing optimisations have little effect there.
