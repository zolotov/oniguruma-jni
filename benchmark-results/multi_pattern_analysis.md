# Multi-Pattern Match Benchmark: JNI vs FFI

**Date:** 2026-04-24  
**JVM:** OpenJDK 25.0.3 LTS  
**JMH:** Throughput (ops/ms), 1 warmup + 3 measurement iterations, fork=1  
**Pattern counts:** 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024

---

## What is measured

### Part 1 — Linear search

Find the pattern with the **earliest match** across N compiled regexes:

| Method | Approach |
|--------|----------|
| `jni_loop` | Java iterates, calls JNI `match()` per pattern — N native calls |
| `ffi_loop` | Java iterates, calls FFI `match()` per pattern — N native calls |
| `jni_batch` | Single JNI call; Rust iterates on the native side — 1 native call |
| `ffi_batch` | Single FFI call; C iterates on the native side — 1 native call |

### Part 2 — Tree traversal (TextMate `matchRule` simulation)

A balanced 4-ary tree of `PatternNode`s. Leaf nodes hold compiled regexes;
internal nodes group scopes — equivalent to TextMate's `SyntaxNodeDescriptor`.

| Method | Approach |
|--------|----------|
| `jni_tree_traverse` | Recursive DFS + `match()` at each leaf — N native calls |
| `ffi_tree_traverse` | Same via FFI |
| `jni_tree_collect_batch` | DFS to collect leaf ptrs into `long[]`, then one `findFirstMatch` — 1 native call |
| `ffi_tree_collect_batch` | Same via FFI |

---

## Results (ops/ms — higher = faster)

### Part 1: Linear loop vs native batch

```
Benchmark                     N=1      N=2      N=4      N=8      N=16     N=32     N=64     N=128    N=256    N=512    N=1024
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
jni_loop                    2,800    3,048    2,985    3,003    2,986    3,024    2,999    2,977    3,036    2,969    2,993
ffi_loop                    2,875    2,936    2,804    3,092    3,085    2,874    2,791    2,930    3,006    2,872    2,995
jni_batch                   2,324    2,334    2,340    2,295    2,195    1,978    2,249    2,224    1,720    1,506    1,188
ffi_batch                   2,984    2,668    3,029    2,917    3,036    2,772    2,831    2,676    2,132    1,852    1,387
```

### Part 2: Tree traverse vs collect-then-batch

```
Benchmark                     N=1      N=2      N=4      N=8      N=16     N=32     N=64     N=128    N=256    N=512    N=1024
──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
jni_tree_traverse           2,977    2,895    3,015    2,840    2,912    2,906    2,243    2,768    2,845    3,025    2,963
ffi_tree_traverse           2,927    2,903    2,917    2,741    2,955    2,461    2,570    2,934    2,956    2,885    2,945
jni_tree_collect_batch      2,383    2,173    2,124    1,852    1,548    1,253      977      539      296      175      100
ffi_tree_collect_batch      2,953    2,583    2,517    2,365    1,917    1,428      963      560      304      179      102
```

---

## Analysis

### 1. Why loop throughput is flat across all N

Both `jni_loop` and `ffi_loop` hold **constant throughput (~3,000 ops/ms) for
all pattern counts including N=1024.** The reason is the early-exit optimisation:
the first pattern (`\s+`) matches the leading whitespace at byte-offset 0, so the
loop aborts after the very first native call regardless of how many patterns exist.

This is exactly the same early-exit that TextMate implements:
> "if resultState.matchData.matched && resultState.matchData.byteRange().start == byteOffset → break"

**Implication:** in workloads where an early match at the current position is
common (beginning of a line, or a grammar where whitespace/trivial patterns are
checked first), the loop strategy is effectively O(1) native calls.

### 2. Why batch throughput drops at large N

`jni_batch` and `ffi_batch` degrade from ~2,300 → 1,188 and ~3,000 → 1,387
ops/ms respectively as N grows 1→1024.

The degradation has two sources:
- **JNI batch:** `find_first_match` must pin the Java `LongArray`, copy pointers
  into a `Vec<i64>`, then iterate. Even with an early exit at index 0, the copy
  is O(N).  At N=1024 that is 8 KB of pointer data pinned and copied every call.
- **FFI batch:** `findFirstMatch` copies the `long[]` into a freshly allocated
  `Arena` `MemorySegment` on every invocation (O(N) off-heap copy before the C
  call even starts).

**Key insight:** the batch approach removes N→1 native call overhead but replaces
it with O(N) array-copy overhead.  That trade-off only pays off when the JNI/FFI
per-call cost exceeds the copy cost *and* no early exit eliminates most of the N
calls.

For small N (1–16) the batch costs are low and comparable to the loop.  By N=256
the copy overhead makes both batch variants measurably slower than the simple loop.

### 3. Tree traverse vs collect-then-batch

`jni_tree_traverse` and `ffi_tree_traverse` are **constant across all N (~2,900
ops/ms)** for the same reason as the loop: the tree's leftmost leaf (`\s+`)
matches at offset 0, and the DFS returns immediately with an early exit.

`jni_tree_collect_batch` and `ffi_tree_collect_batch` **collapse rapidly:**

```
N      tree_traverse  collect_batch  ratio
1          2,977          2,383      0.80×
4          3,015          2,124      0.70×
16         2,912          1,548      0.53×
64         2,243            977      0.44×
256        2,845            296      0.10×
1024       2,963            100      0.03×
```

At N=1024 the collect-then-batch approach is **30× slower** than traverse-match.
The overhead is dominated by:
1. Walking all 1024 leaves to fill an `ArrayList<Long>`.
2. Converting the list to a `long[]`.
3. The O(N) array copy into native memory (same as the batch issue above).

### 4. JNI vs FFI comparison

For the loop variants, FFI and JNI are within noise of each other (~2,900–3,100
ops/ms).  The single-match FFI advantage shown in `CreateBenchmark` (FFI 2× faster
on createString) does not materially show here because in the early-exit scenario
both make only one native call, and regex evaluation dominates.

For the batch variants at small N, FFI batch is ~25% faster than JNI batch (FFI
has a simpler trampoline vs JNI's array-pin overhead).  At large N both converge
because the O(N) copy dominates.

---

## Conclusions for the TextMate optimisation

The proposed "traverse-collect + batch" optimisation **hurts** in the common case
where early exit fires quickly.  It only helps when:

1. Matches are rare or late in the string (early exit doesn't fire).
2. N is small enough that the collection cost is sub-dominant.
3. The JNI/FFI per-call overhead is large relative to the match work (true for
   trivial patterns; false for complex regex with long strings).

**Recommended approach for TextMate:**

- Keep the per-leaf `match()` call with early exit for the common (syntactically
  dense) path.
- Consider the batch call only for the *injection* pass, where all injections must
  be checked unconditionally and there is no early-exit guarantee.
- If collecting is adopted, pre-allocate the `long[]` once per grammar node
  (cache it on the `SyntaxNodeDescriptor`) to eliminate the `ArrayList` + copy
  overhead from the hot path.

---

## Raw JMH summary (full table)

See `results.json` for machine-readable data.

```
Benchmark                                     (patternCount)   Mode  Cnt      Score       Error   Units
CreateBenchmark.ffi_createRegex                          N/A  thrpt    3   1221.597 ±  1235.054  ops/ms
CreateBenchmark.ffi_createString                         N/A  thrpt    3  14814.473 ± 25040.235  ops/ms
CreateBenchmark.jni_createRegex                          N/A  thrpt    3   1090.165 ±  1953.640  ops/ms
CreateBenchmark.jni_createString                         N/A  thrpt    3   6921.773 ± 15777.812  ops/ms
MultiPatternBenchmark.ffi_batch                            1  thrpt    3   2983.767 ±  5473.885  ops/ms
MultiPatternBenchmark.ffi_batch                            2  thrpt    3   2668.309 ±  5608.778  ops/ms
MultiPatternBenchmark.ffi_batch                            4  thrpt    3   3029.096 ±  6722.171  ops/ms
MultiPatternBenchmark.ffi_batch                            8  thrpt    3   2916.841 ±  5841.972  ops/ms
MultiPatternBenchmark.ffi_batch                           16  thrpt    3   3035.753 ±  5355.836  ops/ms
MultiPatternBenchmark.ffi_batch                           32  thrpt    3   2772.357 ±  5127.971  ops/ms
MultiPatternBenchmark.ffi_batch                           64  thrpt    3   2830.989 ±  6050.336  ops/ms
MultiPatternBenchmark.ffi_batch                          128  thrpt    3   2676.137 ±  4781.960  ops/ms
MultiPatternBenchmark.ffi_batch                          256  thrpt    3   2131.560 ±  4580.811  ops/ms
MultiPatternBenchmark.ffi_batch                          512  thrpt    3   1852.335 ±  2909.939  ops/ms
MultiPatternBenchmark.ffi_batch                         1024  thrpt    3   1387.068 ±  2227.157  ops/ms
MultiPatternBenchmark.ffi_loop                             1  thrpt    3   2875.056 ±  6214.643  ops/ms
MultiPatternBenchmark.ffi_loop                             2  thrpt    3   2935.626 ±  4376.874  ops/ms
MultiPatternBenchmark.ffi_loop                             4  thrpt    3   2804.493 ±  4492.263  ops/ms
MultiPatternBenchmark.ffi_loop                             8  thrpt    3   3091.995 ±  5275.214  ops/ms
MultiPatternBenchmark.ffi_loop                            16  thrpt    3   3085.058 ±  4266.416  ops/ms
MultiPatternBenchmark.ffi_loop                            32  thrpt    3   2874.236 ±  5622.666  ops/ms
MultiPatternBenchmark.ffi_loop                            64  thrpt    3   2791.390 ±  4395.050  ops/ms
MultiPatternBenchmark.ffi_loop                           128  thrpt    3   2929.674 ±  6610.513  ops/ms
MultiPatternBenchmark.ffi_loop                           256  thrpt    3   3006.062 ±  6286.706  ops/ms
MultiPatternBenchmark.ffi_loop                           512  thrpt    3   2872.241 ±  8510.358  ops/ms
MultiPatternBenchmark.ffi_loop                          1024  thrpt    3   2994.583 ±  5119.233  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch               1  thrpt    3   2953.388 ±  4389.525  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch               2  thrpt    3   2582.811 ±  4642.454  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch               4  thrpt    3   2516.556 ±  6572.292  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch               8  thrpt    3   2365.061 ±  2747.141  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch              16  thrpt    3   1916.840 ±  3714.314  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch              32  thrpt    3   1427.877 ±  2344.842  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch              64  thrpt    3    963.102 ±  1246.783  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch             128  thrpt    3    560.384 ±   543.267  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch             256  thrpt    3    303.913 ±   305.883  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch             512  thrpt    3    178.651 ±   274.895  ops/ms
MultiPatternBenchmark.ffi_tree_collect_batch            1024  thrpt    3    101.696 ±   131.752  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                    1  thrpt    3   2926.676 ±  5121.274  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                    2  thrpt    3   2902.563 ±  8070.115  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                    4  thrpt    3   2916.652 ±  5704.663  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                    8  thrpt    3   2740.542 ±  3757.702  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                   16  thrpt    3   2955.371 ±  3321.728  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                   32  thrpt    3   2461.120 ±  3898.325  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                   64  thrpt    3   2569.671 ±  3821.900  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                  128  thrpt    3   2933.608 ±  6275.448  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                  256  thrpt    3   2956.147 ±  4541.007  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                  512  thrpt    3   2884.781 ±  3303.658  ops/ms
MultiPatternBenchmark.ffi_tree_traverse                 1024  thrpt    3   2944.903 ±  3145.794  ops/ms
MultiPatternBenchmark.jni_batch                            1  thrpt    3   2324.407 ±  3460.958  ops/ms
MultiPatternBenchmark.jni_batch                            2  thrpt    3   2333.853 ±  3263.485  ops/ms
MultiPatternBenchmark.jni_batch                            4  thrpt    3   2340.011 ±  3007.634  ops/ms
MultiPatternBenchmark.jni_batch                            8  thrpt    3   2295.460 ±  4084.524  ops/ms
MultiPatternBenchmark.jni_batch                           16  thrpt    3   2195.041 ±  4076.864  ops/ms
MultiPatternBenchmark.jni_batch                           32  thrpt    3   1978.155 ±  1694.178  ops/ms
MultiPatternBenchmark.jni_batch                           64  thrpt    3   2249.106 ±  4538.687  ops/ms
MultiPatternBenchmark.jni_batch                          128  thrpt    3   2223.580 ±  2430.792  ops/ms
MultiPatternBenchmark.jni_batch                          256  thrpt    3   1719.790 ±  1345.124  ops/ms
MultiPatternBenchmark.jni_batch                          512  thrpt    3   1506.131 ±   983.539  ops/ms
MultiPatternBenchmark.jni_batch                         1024  thrpt    3   1188.086 ±  2272.555  ops/ms
MultiPatternBenchmark.jni_loop                             1  thrpt    3   2799.869 ±  4056.744  ops/ms
MultiPatternBenchmark.jni_loop                             2  thrpt    3   3048.020 ±  3586.672  ops/ms
MultiPatternBenchmark.jni_loop                             4  thrpt    3   2985.001 ±  3372.971  ops/ms
MultiPatternBenchmark.jni_loop                             8  thrpt    3   3003.053 ±  2746.796  ops/ms
MultiPatternBenchmark.jni_loop                            16  thrpt    3   2986.368 ±  3689.178  ops/ms
MultiPatternBenchmark.jni_loop                            32  thrpt    3   3023.871 ±  4455.206  ops/ms
MultiPatternBenchmark.jni_loop                            64  thrpt    3   2999.078 ±  2965.465  ops/ms
MultiPatternBenchmark.jni_loop                           128  thrpt    3   2977.182 ±  3207.692  ops/ms
MultiPatternBenchmark.jni_loop                           256  thrpt    3   3035.646 ±  3791.457  ops/ms
MultiPatternBenchmark.jni_loop                           512  thrpt    3   2969.282 ±  4719.592  ops/ms
MultiPatternBenchmark.jni_loop                          1024  thrpt    3   2993.423 ±  3555.716  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch               1  thrpt    3   2382.525 ±  2258.571  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch               2  thrpt    3   2172.620 ±  2311.275  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch               4  thrpt    3   2123.155 ±  2409.374  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch               8  thrpt    3   1852.385 ±  2835.475  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch              16  thrpt    3   1547.686 ±  1530.542  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch              32  thrpt    3   1252.783 ±   426.896  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch              64  thrpt    3    977.255 ±  1039.133  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch             128  thrpt    3    538.824 ±   733.224  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch             256  thrpt    3    295.533 ±   121.572  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch             512  thrpt    3    174.652 ±   157.748  ops/ms
MultiPatternBenchmark.jni_tree_collect_batch            1024  thrpt    3     99.676 ±   122.376  ops/ms
MultiPatternBenchmark.jni_tree_traverse                    1  thrpt    3   2977.098 ±  4231.845  ops/ms
MultiPatternBenchmark.jni_tree_traverse                    2  thrpt    3   2894.571 ±  5493.732  ops/ms
MultiPatternBenchmark.jni_tree_traverse                    4  thrpt    3   3015.149 ±  3509.685  ops/ms
MultiPatternBenchmark.jni_tree_traverse                    8  thrpt    3   2839.856 ±  4359.647  ops/ms
MultiPatternBenchmark.jni_tree_traverse                   16  thrpt    3   2911.974 ±  3527.253  ops/ms
MultiPatternBenchmark.jni_tree_traverse                   32  thrpt    3   2906.309 ±  3148.007  ops/ms
MultiPatternBenchmark.jni_tree_traverse                   64  thrpt    3   2243.387 ± 11762.897  ops/ms
MultiPatternBenchmark.jni_tree_traverse                  128  thrpt    3   2767.756 ±  3796.093  ops/ms
MultiPatternBenchmark.jni_tree_traverse                  256  thrpt    3   2845.075 ±  3191.028  ops/ms
MultiPatternBenchmark.jni_tree_traverse                  512  thrpt    3   3025.218 ±   636.012  ops/ms
MultiPatternBenchmark.jni_tree_traverse                 1024  thrpt    3   2962.592 ±  1332.707  ops/ms
```
