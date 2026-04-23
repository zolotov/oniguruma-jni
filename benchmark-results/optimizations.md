# Suggested Optimizations — JNI and FFI

Generated after the JNI vs FFI benchmark run (2026-04-23).
See `comparison.md` for the baseline numbers.

---

## Panama FFI optimizations

### 1. Reuse arenas instead of allocating per call

The current `OnigurumaFFI` implementation creates a new `Arena.ofConfined()` on
every `createString` / `createRegex` call:

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment seg = arena.allocateFrom(ValueLayout.JAVA_BYTE, pattern);
    ...
}
```

For hot paths, pre-allocate a thread-local `MemorySegment` large enough for
the biggest expected input and reuse it as a staging buffer:

```java
private static final ThreadLocal<MemorySegment> STAGING =
    ThreadLocal.withInitial(() ->
        Arena.ofAuto().allocate(4096, 1));  // resize as needed

public long createString(byte[] utf8Content) {
    MemorySegment seg = STAGING.get();
    if (seg.byteSize() < utf8Content.length) {
        seg = Arena.ofAuto().allocate(utf8Content.length * 2L, 1);
        STAGING.set(seg);
    }
    MemorySegment.copy(utf8Content, 0, seg, ValueLayout.JAVA_BYTE, 0, utf8Content.length);
    ...
}
```

This eliminates the bump-pointer allocator overhead and avoids GC pressure from
short-lived `Arena` objects — the root cause of the wider error bar seen in the
`ffi_createString` result (±3062 vs JNI's ±572).

### 2. Cache `MethodHandle.asType()` specialization

`invokeExact` requires an exact type match at the call site. Any mismatch
silently falls back to `invoke` (with an extra type-check per call). Explicitly
specialize each handle at static-init time so the JIT sees a monomorphic site:

```java
CREATE_REGEX = linker.downcallHandle(...)
    .asType(MethodType.methodType(long.class, MemorySegment.class, int.class));
```

### 3. Use `Linker.Option.isTrivial()` for free operations (Java 23+)

`oni_free_string` and `oni_free_regex` are pure C calls — they hold no Java
references, never call back into the JVM, and never block. Marking them as
trivial avoids a safepoint transition on every invocation:

```java
FREE_REGEX = linker.downcallHandle(
    lookup.find("oni_free_regex").orElseThrow(),
    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG),
    Linker.Option.isTrivial()   // skip safepoint poll
);
```

### 4. Return match regions via a `StructLayout`

The current implementation copies the `int[]` result out of a `MemorySegment`
with a scalar loop. Wrapping the region array in a `StructLayout` of fixed size
lets the JIT use bulk memory moves (and potentially SIMD) for the copy:

```java
SequenceLayout REGIONS_LAYOUT =
    MemoryLayout.sequenceLayout(MAX_REGIONS * 2, ValueLayout.JAVA_INT);
```

---

## JNI / Rust optimizations

### 1. Use `GetPrimitiveArrayCritical` in `create_string`

`create_string` currently calls `get_array_elements` which may copy the Java
array. The `Critical` variant pins the Java heap object in place without a copy,
at the cost of disabling GC for the duration of the pin (acceptable for a
nanosecond-scale `memcpy`):

```rust
// Rust jni crate equivalent: get_primitive_array_critical
let elements = env.get_primitive_array_critical(&p, ReleaseMode::NoCopyBack)?;
```

### 2. Expose a combined `createStringAndRegex` entry point

`createString` and `createRegex` are always called together before the first
`match`. A single JNI call that compiles the pattern against the already-owned
string buffer eliminates one round-trip across the JNI boundary and one heap
allocation:

```kotlin
// New combined API
external fun createStringAndRegex(
    utf8Content: ByteArray,
    pattern: ByteArray,
): LongArray   // [stringPtr, regexPtr]
```

### 3. Thread-local `Region` cache in `match_pattern`

Every call to `match_pattern` allocates and frees an `OnigRegion` via
`Region::new()` / `region.drop()`. A thread-local cached region (cleared with
`onig_region_clear` between uses) avoids the malloc/free per match:

```rust
thread_local! {
    static REGION: RefCell<Region> = RefCell::new(Region::new());
}

fn match_pattern(...) -> Result<jintArray> {
    REGION.with(|r| {
        let mut region = r.borrow_mut();
        region.clear();
        // use &mut *region instead of a fresh Region
        ...
    })
}
```

### 4. Cache the `RuntimeException` class as a global JNI reference

`propagate_exception` calls `env.find_class("java/lang/RuntimeException")` on
every error. Cache a `GlobalRef` to the class in `JNI_OnLoad` to avoid the
class lookup on the error hot path:

```rust
static RUNTIME_EXCEPTION: OnceLock<GlobalRef> = OnceLock::new();

#[no_mangle]
pub extern "C" fn JNI_OnLoad(vm: JavaVM, _: *mut c_void) -> jint {
    let env = vm.get_env(...).unwrap();
    let cls = env.find_class("java/lang/RuntimeException").unwrap();
    RUNTIME_EXCEPTION.set(env.new_global_ref(cls).unwrap()).ok();
    JNI_VERSION_1_8
}
```
