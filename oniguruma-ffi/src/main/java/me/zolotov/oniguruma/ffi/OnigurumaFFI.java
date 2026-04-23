package me.zolotov.oniguruma.ffi;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Java 25 Panama FFI binding for the Oniguruma regex library.
 * Mirrors the JNI API in {@code me.zolotov.oniguruma.Oniguruma} so both can
 * be benchmarked side-by-side.
 */
public final class OnigurumaFFI {

    private static final MethodHandle CREATE_REGEX;
    private static final MethodHandle FREE_REGEX;
    private static final MethodHandle CREATE_STRING;
    private static final MethodHandle FREE_STRING;
    private static final MethodHandle MATCH;

    static {
        String libPath = System.getProperty("oniguruma.ffi.lib");
        if (libPath == null) {
            throw new IllegalStateException(
                "System property 'oniguruma.ffi.lib' must point to liboniguruma_ffi.so");
        }
        System.load(libPath);

        Linker        linker = Linker.nativeLinker();
        SymbolLookup  lookup = SymbolLookup.loaderLookup();

        CREATE_REGEX = linker.downcallHandle(
            lookup.find("oni_create_regex").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );

        FREE_REGEX = linker.downcallHandle(
            lookup.find("oni_free_regex").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG)
        );

        CREATE_STRING = linker.downcallHandle(
            lookup.find("oni_create_string").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
        );

        FREE_STRING = linker.downcallHandle(
            lookup.find("oni_free_string").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG)
        );

        MATCH = linker.downcallHandle(
            lookup.find("oni_match").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,  // reg_ptr
                ValueLayout.JAVA_LONG,  // str_ptr
                ValueLayout.JAVA_INT,   // byte_offset
                ValueLayout.JAVA_INT,   // match_begin_position
                ValueLayout.JAVA_INT,   // match_begin_string
                ValueLayout.ADDRESS,    // regions_out
                ValueLayout.JAVA_INT)   // max_regions
        );
    }

    public long createRegex(byte[] pattern) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocateFrom(ValueLayout.JAVA_BYTE, pattern);
            return (long) CREATE_REGEX.invokeExact(seg, pattern.length);
        } catch (Throwable t) {
            throw new RuntimeException("oni_create_regex failed", t);
        }
    }

    public void freeRegex(long regexPtr) {
        try {
            FREE_REGEX.invokeExact(regexPtr);
        } catch (Throwable t) {
            throw new RuntimeException("oni_free_regex failed", t);
        }
    }

    public long createString(byte[] utf8Content) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocateFrom(ValueLayout.JAVA_BYTE, utf8Content);
            return (long) CREATE_STRING.invokeExact(seg, utf8Content.length);
        } catch (Throwable t) {
            throw new RuntimeException("oni_create_string failed", t);
        }
    }

    public void freeString(long textPtr) {
        try {
            FREE_STRING.invokeExact(textPtr);
        } catch (Throwable t) {
            throw new RuntimeException("oni_free_string failed", t);
        }
    }

    public int[] match(long regexPtr, long textPtr, int byteOffset,
                       boolean matchBeginPosition, boolean matchBeginString) {
        final int MAX_REGIONS = 256;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT, (long) MAX_REGIONS * 2);
            int count = (int) MATCH.invokeExact(
                regexPtr, textPtr, byteOffset,
                matchBeginPosition ? 1 : 0,
                matchBeginString   ? 1 : 0,
                out, MAX_REGIONS
            );
            if (count < 0) return null;
            int[] result = new int[count * 2];
            MemorySegment.copy(out, ValueLayout.JAVA_INT, 0, result, 0, count * 2);
            return result;
        } catch (Throwable t) {
            throw new RuntimeException("oni_match failed", t);
        }
    }
}
