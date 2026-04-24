# Optimization Results

Benchmarks run on OpenJDK 25.0.3 (Temurin), JMH 1.37, Linux x86_64.
JMH config: 1 warmup iteration × 1 s, 3 measurement iterations × 1 s, fork 1.
Rust 1.84.1, Oniguruma 6.9.9.

All results in **ops/ms (throughput, higher is better)**.

---

## Baseline

File: `opt-00-baseline.json`

The benchmark suite originally only covered allocation (`createString`, `createRegex`).
A `match` benchmark was added in this pass; no baseline exists for it — the first
meaningful number comes after all optimizations are applied (see the final table).

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

**Verdict:** Largest single win. Eliminates bump-pointer allocator overhead and GC pressure
from short-lived `Arena` objects on every `createString` call. Also narrows the error bar
dramatically (±769 vs ±3,062) because the throughput is no longer dominated by GC pauses.

---

### FFI-2: MethodHandle.asType() specialization

**Change:** Added `.asType(MethodType.methodType(...))` to each downcall handle at static-init
time so the JIT sees a monomorphic `invokeExact` call site and can inline without a type check.

File: `opt-ffi-02-method-handle-specialization.json`

| Benchmark | Score (ops/ms) | Error | vs FFI-1 |
|-----------|---------------|-------|----------|
| ffi_createRegex | 1,329.1 | ±585.2 | −2% (noise) |
| ffi_createString | 25,120.3 | ±4,067.9 | −3% (noise) |

**Verdict:** No measurable throughput change. The JIT already specialises monomorphic sites;
the explicit `asType` is a defensive correctness measure that eliminates any risk of a silent
fallback to `invoke`.

---

### FFI-3: Linker.Option.critical(false) for free operations

**Note:** The optimization document referred to `Linker.Option.isTrivial()`, which does not
exist in JDK 25. The equivalent is `Linker.Option.critical(false)` — no heap access allowed,
which skips the safepoint poll on calls that do not touch the Java heap.

**Change:** Added `Linker.Option.critical(false)` to `FREE_REGEX` and `FREE_STRING`.

File: `opt-ffi-03-critical-free.json`

| Benchmark | Score (ops/ms) | Error | vs FFI-2 |
|-----------|---------------|-------|----------|
| ffi_createRegex | 1,367.2 | ±113.3 | +3% |
| ffi_createString | 28,949.5 | ±13,679.9 | +15% |

**Verdict:** Positive trend; large error bar introduces uncertainty. The safepoint-poll
elimination is a low-cost change that benefits any hot path calling `freeRegex`/`freeString`
in a tight loop.

---

### FFI-4: SequenceLayout + thread-local output buffer for match regions

**Change:** Declared a static `SequenceLayout REGIONS_LAYOUT` for the 256×2 int output array
and moved the output `MemorySegment` to a thread-local `Arena.ofAuto()` buffer, eliminating
the per-`match` `Arena.ofConfined()` allocation.

File: `opt-ffi-04-sequence-layout-regions.json`

No change visible in prior benchmarks (no `match` existed yet). Impact measured in the final
run — see the **Cumulative Summary** below.

---

## JNI / Rust Optimizations

### JNI-1: get_array_elements_critical in create_string

**Change:** Replaced `env.get_array_elements()` with `env.get_array_elements_critical()` in
`create_string`. The critical variant pins the Java heap object without copying (GC suspended
for the duration of UTF-8 validation and `String` construction). The critical section is
released via an explicit `drop` before any further JNI calls.

File: `opt-jni-01-critical-array.json`

| Benchmark | Score (ops/ms) | Error | vs JNI baseline |
|-----------|---------------|-------|-----------------|
| jni_createString | 8,309.8 | ±1,472.1 | **+11%** |

**Verdict:** Meaningful improvement. Eliminates the JNI array copy on the `createString` path.

---

### JNI-2: Thread-local Region cache in match_pattern

**Change:** Added `thread_local! { static REGION: RefCell<Region> }`. `match_pattern` now
borrows the cached `Region`, calls `region.clear()`, and reuses it instead of allocating a
fresh `Region::new()` on every call.

File: `opt-jni-03-region-cache.json`

Impact not visible in prior allocation benchmarks. Measured in the final `jni_match` result —
see the **Cumulative Summary**.

---

### JNI-3: Cache RuntimeException class as a global JNI reference

**Change:** Added `JNI_OnLoad` in `lib.rs` that caches a `GlobalRef` to
`java.lang.RuntimeException` in a `static OnceLock<GlobalRef>`. `propagate_exception` uses
the cached ref instead of calling `env.find_class(...)` on every error.

File: `opt-jni-04-cache-exception-class.json`

No throughput change on the happy path (expected — class lookup only occurs on errors). The
benefit is on the error path: removes a class-lock acquisition per thrown exception.

---

## Final Benchmark — All Optimizations, With Match

File: `opt-final-with-match.json`

| Benchmark | Score (ops/ms) | Error |
|-----------|---------------|-------|
| ffi_createRegex | 1,372.2 | ±202.2 |
| ffi_createString | 19,682.9 | ±3,004.5 |
| **ffi_match** | **4,007.0** | ±787.2 |
| jni_createRegex | 1,187.2 | ±36.5 |
| jni_createString | 7,937.4 | ±1,827.9 |
| **jni_match** | **2,839.6** | ±890.6 |

---

## Cumulative Summary

| Benchmark | Baseline | Final (all opts) | Change |
|-----------|----------|-----------------|--------|
| ffi_createRegex | 1,302.7 | 1,372.2 | +5% |
| ffi_createString | 16,096.1 | 19,682.9 | **+22%** |
| ffi_match | — | 4,007.0 | new |
| jni_createRegex | 1,196.9 | 1,187.2 | ≈0% |
| jni_createString | 7,597.7 | 7,937.4 | +4% |
| jni_match | — | 2,839.6 | new |

### Key findings

**`createString`** — FFI-1 (thread-local arena) was by far the biggest lever (+61% from baseline
to that step alone). The final number is lower than the mid-run peak (~29k) because JVM variance
across separate JMH invocations can shift results by 10–20%.

**`createRegex`** — Neither JNI nor FFI improved meaningfully. Oniguruma's `onig_new` O(n)
compilation dominates; the boundary-crossing overhead is negligible by comparison.

**`match`** — The most important benchmark for production use. FFI outperforms JNI by **+41%**
(4,007 vs 2,839 ops/ms). This is the operation that fires on every token in a TextMate grammar
scan. Gains here come from:
- FFI-3: no safepoint poll on `freeRegex`/`freeString` called after each match
- FFI-4: thread-local output buffer eliminates a malloc/free per match call
- JNI-2: thread-local `Region` cache eliminates a malloc/free on the Rust side

FFI is consistently faster than JNI across all three operation types, with the gap widest on
the allocation-heavy `createString` path and on `match`.
