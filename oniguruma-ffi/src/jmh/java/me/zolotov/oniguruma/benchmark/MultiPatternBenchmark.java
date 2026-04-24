package me.zolotov.oniguruma.benchmark;

import me.zolotov.oniguruma.Oniguruma;
import me.zolotov.oniguruma.ffi.OnigurumaFFI;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks two scenarios for multi-pattern matching:
 *
 * 1. LINEAR — find the pattern with the earliest match by iterating 1..1024 patterns.
 *    a) jni/ffi_loop  : Java iterates, calls match() per pattern (N native calls).
 *    b) jni/ffi_batch : single native call that iterates on the C/Rust side (1 native call).
 *
 * 2. TREE — replicate TextMate's recursive matchRule algorithm where a tree of
 *    SyntaxNodeDescriptors is traversed looking for the earliest match.
 *    a) jni/ffi_tree_traverse : traverse + match at every leaf (N native calls).
 *    b) jni/ffi_tree_collect  : traverse to collect all leaf ptrs, then one batch call.
 *
 * The @Param drives the total leaf-pattern count (1, 2, 4, … 1024).
 * The tree is a balanced 4-ary tree whose leaves hold the compiled regex pointers.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class MultiPatternBenchmark {

    // ── Patterns ─────────────────────────────────────────────────────────────

    /**
     * Ten realistic TextMate-style patterns that produce matches in TEXT.
     * For N > 10 we cycle through them; the benchmark measures call overhead,
     * not pattern diversity.
     */
    private static final String[] BASE_PATTERNS = {
        "\\s+",
        "\\b(function|return|if|else|while|for|var|let|const|class)\\b",
        "[0-9]+\\.?[0-9]*([eE][+-]?[0-9]+)?",
        "\"([^\"\\\\]|\\\\.)*\"",
        "'([^'\\\\]|\\\\.)*'",
        "//[^\\n]*",
        "/\\*[\\s\\S]*?\\*/",
        "[a-zA-Z_$][a-zA-Z0-9_$]*",
        "[+\\-*/%=<>!&|^~?:]+",
        "[(){}\\[\\],.;]",
    };

    /** A representative source-code line; several patterns match at different offsets. */
    private static final byte[] TEXT =
        "  function hello(x, y) { return x * 2 + y; } // comment \"str\" 42"
            .getBytes(StandardCharsets.UTF_8);

    // ── JMH parameter ────────────────────────────────────────────────────────

    @Param({"1", "2", "4", "8", "16", "32", "64", "128", "256", "512", "1024"})
    public int patternCount;

    // ── State ─────────────────────────────────────────────────────────────────

    private Oniguruma    jni;
    private OnigurumaFFI ffi;

    private long   jniStringPtr;
    private long   ffiStringPtr;

    // Pre-compiled regex handles indexed [0, patternCount)
    private long[] jniPtrs;
    private long[] ffiPtrs;

    // Tree roots built from the same handles
    private PatternNode jniTree;
    private PatternNode ffiTree;

    // ── Setup / teardown ─────────────────────────────────────────────────────

    @Setup(Level.Trial)
    public void setup() {
        jni = Oniguruma.Companion.createFromResources();
        ffi = new OnigurumaFFI();

        jniStringPtr = jni.createString(TEXT);
        ffiStringPtr = ffi.createString(TEXT);

        jniPtrs = new long[patternCount];
        ffiPtrs = new long[patternCount];
        for (int i = 0; i < patternCount; i++) {
            byte[] pat = BASE_PATTERNS[i % BASE_PATTERNS.length]
                .getBytes(StandardCharsets.UTF_8);
            jniPtrs[i] = jni.createRegex(pat);
            ffiPtrs[i] = ffi.createRegex(pat);
        }

        jniTree = buildTree(jniPtrs, 0, patternCount);
        ffiTree = buildTree(ffiPtrs, 0, patternCount);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        jni.freeString(jniStringPtr);
        ffi.freeString(ffiStringPtr);
        for (int i = 0; i < patternCount; i++) {
            jni.freeRegex(jniPtrs[i]);
            ffi.freeRegex(ffiPtrs[i]);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 1 — LINEAR: iterate patterns, return earliest match
    // ══════════════════════════════════════════════════════════════════════════

    /** Java loop over JNI match() — N native round-trips. */
    @Benchmark
    public void jni_loop(Blackhole bh) {
        bh.consume(javaLoopFindFirst(jniPtrs, jniStringPtr, jni));
    }

    /** Java loop over FFI match() — N native round-trips. */
    @Benchmark
    public void ffi_loop(Blackhole bh) {
        bh.consume(javaLoopFindFirst(ffiPtrs, ffiStringPtr, ffi));
    }

    /** Single JNI call — Rust iterates on the native side. */
    @Benchmark
    public void jni_batch(Blackhole bh) {
        bh.consume(jni.findFirstMatch(jniPtrs, jniStringPtr, 0, true, true));
    }

    /** Single FFI call — C iterates on the native side. */
    @Benchmark
    public void ffi_batch(Blackhole bh) {
        bh.consume(ffi.findFirstMatch(ffiPtrs, ffiStringPtr, 0, true, true));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PART 2 — TREE: TextMate-style recursive matchRule
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Replicates the original TextMate matchRule: traverse the tree recursively,
     * call match() at each leaf — N native calls total.
     */
    @Benchmark
    public void jni_tree_traverse(Blackhole bh) {
        bh.consume(traverseAndMatch(jniTree, jniStringPtr, jni));
    }

    @Benchmark
    public void ffi_tree_traverse(Blackhole bh) {
        bh.consume(traverseAndMatch(ffiTree, ffiStringPtr, ffi));
    }

    /**
     * Optimised variant: traverse the tree once to collect all leaf pointers,
     * then issue a single native batch call — 1 native call.
     * Measures whether the O(1) boundary-crossing amortises the collection cost.
     */
    @Benchmark
    public void jni_tree_collect_batch(Blackhole bh) {
        long[] ptrs = collectLeaves(jniTree);
        bh.consume(jni.findFirstMatch(ptrs, jniStringPtr, 0, true, true));
    }

    @Benchmark
    public void ffi_tree_collect_batch(Blackhole bh) {
        long[] ptrs = collectLeaves(ffiTree);
        bh.consume(ffi.findFirstMatch(ptrs, ffiStringPtr, 0, true, true));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** JNI-flavoured Java-loop search (returns int[]{idx,start,end} or null). */
    private static int[] javaLoopFindFirst(long[] ptrs, long strPtr, Oniguruma jni) {
        int   bestIdx   = -1;
        int   bestStart = Integer.MAX_VALUE;
        int   bestEnd   = -1;
        for (int i = 0; i < ptrs.length; i++) {
            int[] m = jni.match(ptrs[i], strPtr, 0, true, true);
            if (m != null && m[0] < bestStart) {
                bestStart = m[0];
                bestEnd   = m[1];
                bestIdx   = i;
                if (bestStart == 0) break; // early exit
            }
        }
        return bestIdx < 0 ? null : new int[]{bestIdx, bestStart, bestEnd};
    }

    /** FFI-flavoured Java-loop search. */
    private static int[] javaLoopFindFirst(long[] ptrs, long strPtr, OnigurumaFFI ffi) {
        int   bestIdx   = -1;
        int   bestStart = Integer.MAX_VALUE;
        int   bestEnd   = -1;
        for (int i = 0; i < ptrs.length; i++) {
            int[] m = ffi.match(ptrs[i], strPtr, 0, true, true);
            if (m != null && m[0] < bestStart) {
                bestStart = m[0];
                bestEnd   = m[1];
                bestIdx   = i;
                if (bestStart == 0) break;
            }
        }
        return bestIdx < 0 ? null : new int[]{bestIdx, bestStart, bestEnd};
    }

    // ── Tree data structure ───────────────────────────────────────────────────

    /**
     * A node in the synthetic pattern tree.
     * Leaf nodes hold a compiled regex pointer; internal nodes hold children.
     * Mirrors TextMate's SyntaxNodeDescriptor: leaves have MATCH/BEGIN patterns,
     * internal nodes group children into scopes.
     */
    static final class PatternNode {
        final long          regexPtr; // valid only when children.length == 0
        final PatternNode[] children; // empty for leaves

        PatternNode(long regexPtr) {
            this.regexPtr = regexPtr;
            this.children = new PatternNode[0];
        }

        PatternNode(PatternNode[] children) {
            this.regexPtr = 0L;
            this.children = children;
        }

        boolean isLeaf() { return children.length == 0; }
    }

    /** Build a balanced 4-ary tree over regexPtrs[from..to). */
    private static PatternNode buildTree(long[] ptrs, int from, int to) {
        if (to - from == 1) return new PatternNode(ptrs[from]);
        int branches = Math.min(4, to - from);
        PatternNode[] children = new PatternNode[branches];
        int step = (to - from + branches - 1) / branches;
        for (int b = 0; b < branches; b++) {
            int s = from + b * step;
            int e = Math.min(s + step, to);
            children[b] = buildTree(ptrs, s, e);
        }
        return new PatternNode(children);
    }

    /**
     * Replicates TextMate's matchRule: recursive DFS, match at each leaf,
     * propagate the earliest match upward with an early-exit at offset 0.
     * Returns int[]{start, end} of the best match, or null.
     */
    private static int[] traverseAndMatch(PatternNode node, long strPtr, Oniguruma jni) {
        if (node.isLeaf()) {
            int[] m = jni.match(node.regexPtr, strPtr, 0, true, true);
            return (m != null) ? new int[]{m[0], m[1]} : null;
        }
        int[] best = null;
        for (PatternNode child : node.children) {
            int[] m = traverseAndMatch(child, strPtr, jni);
            if (m != null && (best == null || m[0] < best[0])) {
                best = m;
                if (best[0] == 0) break; // early exit mirrors TextMate
            }
        }
        return best;
    }

    private static int[] traverseAndMatch(PatternNode node, long strPtr, OnigurumaFFI ffi) {
        if (node.isLeaf()) {
            int[] m = ffi.match(node.regexPtr, strPtr, 0, true, true);
            return (m != null) ? new int[]{m[0], m[1]} : null;
        }
        int[] best = null;
        for (PatternNode child : node.children) {
            int[] m = traverseAndMatch(child, strPtr, ffi);
            if (m != null && (best == null || m[0] < best[0])) {
                best = m;
                if (best[0] == 0) break;
            }
        }
        return best;
    }

    /**
     * Collect all leaf regexPtrs via DFS; used by the collect-then-batch benchmarks.
     * The allocation cost is part of the measured benchmark — it shows whether
     * "traverse-collect + 1 batch call" beats "traverse-match".
     */
    private static long[] collectLeaves(PatternNode root) {
        List<Long> acc = new ArrayList<>();
        collectLeavesInto(root, acc);
        long[] arr = new long[acc.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = acc.get(i);
        return arr;
    }

    private static void collectLeavesInto(PatternNode node, List<Long> acc) {
        if (node.isLeaf()) { acc.add(node.regexPtr); return; }
        for (PatternNode child : node.children) collectLeavesInto(child, acc);
    }
}
