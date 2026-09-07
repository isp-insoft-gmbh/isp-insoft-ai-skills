# Java 26 Foreign Function & Memory API — distilled

Sources:
Oracle Java SE 26 core guide + `java.lang.foreign` API docs.

## What FFM is for

FFM (`java.lang.foreign`) lets Java code:

- call native/foreign functions without JNI glue;
- pass Java code as native function pointers;
- allocate/access off-heap memory safely with bounds/lifetime checks;
- model native structs/unions/arrays/pointers via layouts.

FFM is stable since Java 22.
Java 26 docs describe same core model with JDK 26 API pages.

## Main types

| Type                           | Use                                                                                       |
| ------------------------------ | ----------------------------------------------------------------------------------------- |
| `Arena`                        | Owns lifetime of native segments. Allocate via `allocate`, `allocateFrom`; close to free. |
| `MemorySegment`                | Bounds-checked view over heap/native/mapped memory. Has address, byte size, scope.        |
| `SegmentAllocator`             | Allocation interface implemented by `Arena`; helpers copy Java data to native memory.     |
| `MemoryLayout`                 | Describes size/alignment/field structure; creates var handles.                            |
| `ValueLayout`                  | Primitive layouts (`JAVA_INT`, `JAVA_LONG`, `ADDRESS`, unaligned variants, byte order).   |
| `AddressLayout`                | Pointer layout. Optional target layout enables dereference sizing.                        |
| `SequenceLayout`               | Array layout.                                                                             |
| `StructLayout` / `UnionLayout` | C `struct` / `union` layout. Add explicit padding for ABI.                                |
| `FunctionDescriptor`           | Native function signature: return layout + arg layouts.                                   |
| `Linker`                       | ABI-specific bridge; creates downcall handles and upcall stubs.                           |
| `SymbolLookup`                 | Resolves native symbol names to function addresses.                                       |
| `Linker.Option`                | `firstVariadicArg`, `captureCallState("errno")`, critical/capture options.                |

## Arenas and lifetimes

Use arenas to allocate native/off-heap segments.

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment buf = arena.allocate(1024, 8);
    buf.set(JAVA_INT, 0, 42);
} // closes arena, frees buf backing memory
```

Arena kinds:

- `Arena.ofConfined()` — deterministic lifetime, owner-thread only; default
  choice.
- `Arena.ofShared()` — deterministic lifetime, accessible/closable by multiple
  threads; close is safe/atomic.
- `Arena.ofAuto()` — GC-managed lifetime; any thread; cannot close manually.
- `Arena.global()` — never freed; any thread; cannot close manually.

Closed arena → segment access throws `IllegalStateException`.
Out-of-bounds → `IndexOutOfBoundsException`.
Wrong thread for confined arena → access/close fails.

## Memory segments

Segment kinds:

- heap segment:
  `MemorySegment.ofArray(...)`, backed by Java array;
- native segment:
  allocated by arena;
- mapped segment:
  from `FileChannel.map(..., Arena)`.

Access:

```java
int x = segment.get(JAVA_INT, 0);        // byte offset
segment.set(JAVA_INT, 4, 123);
int y = segment.getAtIndex(JAVA_INT, 3); // logical index, offset = 3 * 4
segment.setAtIndex(JAVA_INT, 3, 456);
```

Strings:

```java
MemorySegment c = arena.allocateFrom("hello"); // UTF-8 + NUL
String s = c.getString(0);
```

Arrays:

```java
MemorySegment nativeInts = arena.allocateFrom(JAVA_INT, new int[] {3, 1, 2});
int[] copy = nativeInts.toArray(JAVA_INT);
```

Slicing:

```java
MemorySegment slice = segment.asSlice(16, 8); // same backing memory, narrower bounds
```

Parallel/disjoint work:
use `Arena.ofShared()` and `segment.elements(layout)` when multiple threads need
access.

## Alignment and endianness

Layouts carry byte size, byte alignment, and byte order.
Segment access validates alignment.

- Native segment address + offset must satisfy layout alignment.
- Heap segment max alignment depends on backing array type.
- For packed/unaligned data use `JAVA_INT_UNALIGNED`, etc.
- For non-native byte order use `layout.withOrder(ByteOrder.BIG_ENDIAN)`.

```java
int be = segment.get(JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN), 0);
```

## C type mapping

Always treat C mapping as ABI/platform-dependent.
Prefer:

```java
Map<String, MemoryLayout> canon = Linker.nativeLinker().canonicalLayouts();
MemoryLayout size_t = canon.get("size_t");
```

All native linkers provide canonical layouts for:
`bool`, `char`, `short`, `int`, `long`, `long long`, `float`, `double`,
`size_t`, `wchar_t`, `void*`.

Typical Linux/x64 mappings:

| C type                            | Java FFM layout                             | Java carrier    |
| --------------------------------- | ------------------------------------------- | --------------- |
| `bool`                            | `JAVA_BOOLEAN`                              | `boolean`       |
| `char`, `unsigned char`           | `JAVA_BYTE`                                 | `byte`          |
| `short`, `unsigned short`         | `JAVA_SHORT`                                | `short`         |
| `int`, `unsigned int`             | `JAVA_INT`                                  | `int`           |
| `long`, `unsigned long`           | `JAVA_LONG`                                 | `long`          |
| `long long`, `unsigned long long` | `JAVA_LONG`                                 | `long`          |
| `float`                           | `JAVA_FLOAT`                                | `float`         |
| `double`                          | `JAVA_DOUBLE`                               | `double`        |
| `size_t`                          | `JAVA_LONG` on 64-bit, `JAVA_INT` on 32-bit | `long`/`int`    |
| `T*`, function pointer            | `ADDRESS`                                   | `MemorySegment` |
| `struct`                          | `structLayout(...)`                         | `MemorySegment` |
| `union`                           | `unionLayout(...)`                          | `MemorySegment` |

Unsigned types use same-size signed carriers; interpret with Java unsigned
helpers (`Integer.toUnsignedLong`, `Long.compareUnsigned`, masks) as needed.

## Downcalls

Downcall = Java → native function.

Pattern:

```java
static final Linker LINKER = Linker.nativeLinker();
static final SymbolLookup LOOKUP = LINKER.defaultLookup();

static final MethodHandle strlen = LINKER.downcallHandle(
    LOOKUP.findOrThrow("strlen"),
    FunctionDescriptor.of(JAVA_LONG, ADDRESS)
);

static long strlen(String s) throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
        return (long) strlen.invokeExact(arena.allocateFrom(s));
    }
}
```

Rules:

- `FunctionDescriptor.of(returnLayout, argLayouts...)`.
- `FunctionDescriptor.ofVoid(argLayouts...)` for `void` return.
- `MethodHandle.invokeExact` requires exact Java carriers and return cast.
- `ADDRESS` args/returns are `MemorySegment`.
- Descriptor must match actual native signature.
  Linker cannot verify; mismatch can crash JVM.

Library lookup:

```java
SymbolLookup std = Linker.nativeLinker().defaultLookup();
// restricted: loads native library; arena controls library lookup lifetime
SymbolLookup lib = SymbolLookup.libraryLookup("libc.so.6", arena);
```

`libraryLookup` is restricted because library loading may execute native code.

## Upcalls

Upcall = native → Java through function pointer.

C:

```c
void qsort(void *base, size_t nmemb, size_t size,
           int (*compar)(const void *, const void *));
```

Java:

```java
static int compareInts(MemorySegment a, MemorySegment b) {
    return Integer.compare(a.get(JAVA_INT, 0), b.get(JAVA_INT, 0));
}

static final Linker LINKER = Linker.nativeLinker();
static final MethodHandle QSORT = LINKER.downcallHandle(
    LINKER.defaultLookup().findOrThrow("qsort"),
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS)
);

static void sort(int[] input) throws Throwable {
    FunctionDescriptor cmpDesc = FunctionDescriptor.of(
        JAVA_INT,
        ADDRESS.withTargetLayout(JAVA_INT),
        ADDRESS.withTargetLayout(JAVA_INT)
    );
    MethodHandle cmp = MethodHandles.lookup().findStatic(
        MyClass.class,
        "compareInts",
        cmpDesc.toMethodType()
    );

    try (Arena arena = Arena.ofConfined()) {
        MemorySegment cmpPtr = LINKER.upcallStub(cmp, cmpDesc, arena);
        MemorySegment arr = arena.allocateFrom(JAVA_INT, input);
        QSORT.invokeExact(arr, (long) input.length, JAVA_INT.byteSize(), cmpPtr);
        int[] sorted = arr.toArray(JAVA_INT);
    }
}
```

Upcall stub lifetime must outlive native use.
If native stores callback pointer, do not use a short-lived confined arena
unless pointer is invalidated before close.

## Pointers returned by native functions and pointer out-parameters

For C output parameters like `T** out`, allocate one pointer-sized slot, pass it
to native code, then read the actual returned pointer:

```java
MemorySegment out = arena.allocate(ADDRESS);
int rc = some_open(..., out);       // C signature: int some_open(..., T **out)
MemorySegment handle = out.get(ADDRESS, 0); // use this T* for later calls
```

Do not pass the slot (`out`) to later functions expecting `T*`; pass `handle`.
This distinction is critical for APIs such as `sqlite3_open(..., sqlite3**)` and
`sqlite3_prepare_v2(..., sqlite3_stmt**)`.

Native pointer returns/read pointers usually become zero-length
`MemorySegment`s:

- address is known;
- size is 0;
- scope is global/always alive;
- direct access throws out-of-bounds.

Attach bounds/lifetime before dereference.

Example `malloc/free` wrapper:

```java
static final Linker LINKER = Linker.nativeLinker();
static final SymbolLookup C = LINKER.defaultLookup();

static final MethodHandle malloc = LINKER.downcallHandle(
    C.findOrThrow("malloc"),
    FunctionDescriptor.of(ADDRESS, JAVA_LONG)
);
static final MethodHandle free = LINKER.downcallHandle(
    C.findOrThrow("free"),
    FunctionDescriptor.ofVoid(ADDRESS)
);

static MemorySegment mallocSegment(long bytes, Arena arena) throws Throwable {
    MemorySegment raw = (MemorySegment) malloc.invokeExact(bytes); // byteSize() == 0
    return raw.reinterpret(bytes, arena, s -> {
        try {
            free.invokeExact(s);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    });
}
```

If bounds statically known for a pointer field/arg:

```java
AddressLayout int4Ptr = ADDRESS.withTargetLayout(sequenceLayout(4, JAVA_INT));
MemorySegment p = segment.get(int4Ptr, offset); // byteSize() = 16
```

`reinterpret` and `AddressLayout.withTargetLayout` are restricted because wrong
bounds/lifetime can corrupt memory/crash JVM.

## Structs, arrays, layouts

C:

```c
struct Point { int x; int y; } pts[10];
```

Java layout + var handles:

```java
static final SequenceLayout POINTS = sequenceLayout(10,
    structLayout(
        JAVA_INT.withName("x"),
        JAVA_INT.withName("y")
    )
);

static final VarHandle X = POINTS.varHandle(sequenceElement(), groupElement("x"));
static final VarHandle Y = POINTS.varHandle(sequenceElement(), groupElement("y"));

try (Arena arena = Arena.ofConfined()) {
    MemorySegment pts = arena.allocate(POINTS);
    X.set(pts, 0L, 3L, 10); // base offset, sequence index, value
    int x = (int) X.get(pts, 0L, 3L);
}
```

For C padding/alignment:

```java
// struct Example { int x; long y; } on many 64-bit ABIs
StructLayout EXAMPLE = structLayout(
    JAVA_INT.withName("x"),
    paddingLayout(4),
    JAVA_LONG.withName("y")
);
```

Native linker function descriptors require well-formed layouts.
Group layouts must include appropriate padding and satisfy natural
alignment/size rules.
Packed structs may be rejected by some linkers.

## Variadic functions

FFM links specialized non-variadic forms.
Use `Linker.Option.firstVariadicArg(index)`.

C:

```c
int printf(const char *format, ...);
```

Java:

```java
MethodHandle printf = LINKER.downcallHandle(
    LINKER.defaultLookup().findOrThrow("printf"),
    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT),
    Linker.Option.firstVariadicArg(1)
);

try (Arena arena = Arena.ofConfined()) {
    int rc = (int) printf.invokeExact(
        arena.allocateFrom("%d plus %d equals %d\n"), 2, 2, 4
    );
}
```

C default promotions apply conceptually; FFM does not auto-promote.
Use promoted layouts for variadic args (`float` → `double`, small ints → `int`).
Linker rejects non-promoted variadic layouts depending on platform.

## Capturing errno

Use `Linker.Option.captureCallState("errno")` on downcall handle and pass
captured-state segment as leading argument to the method handle.

```java
static final Linker.Option CAP_ERRNO = Linker.Option.captureCallState("errno");
static final StructLayout CAPTURED = Linker.Option.captureStateLayout();
static final VarHandle ERRNO = CAPTURED.varHandle(groupElement("errno"));

static final MethodHandle fopen = LINKER.downcallHandle(
    C.findOrThrow("fopen"),
    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
    CAP_ERRNO
);

try (Arena arena = Arena.ofConfined()) {
    MemorySegment state = arena.allocate(CAPTURED);
    MemorySegment file = (MemorySegment) fopen.invokeExact(
        state,
        arena.allocateFrom("missing.txt"),
        arena.allocateFrom("r")
    );
    if (file.address() == 0) {
        int errno = (int) ERRNO.get(state, 0L);
    }
}
```

Use `strerror(errno)` to convert to message if needed; returned string pointer
likely needs `reinterpret(Long.MAX_VALUE).getString(0)` or known bound.
This is restricted/unsafe; prefer bounded APIs if available.

## Restricted methods and native access

Restricted FFM-related operations:

- `Linker.downcallHandle(...)` — signature mismatch is unsafe.
- `Linker.upcallStub(...)` — function pointer type/lifetime mismatch is unsafe.
- `SymbolLookup.libraryLookup(...)` — loading libraries can execute native code.
- `MemorySegment.reinterpret(...)` — changes bounds/lifetime alias.
- `AddressLayout.withTargetLayout(...)` — enables pointer dereference sizing.
- `ModuleLayer.Controller.enableNativeAccess(...)` — propagates native
  privilege.

Enable native access at run time; FFM itself is final in Java 26, so FFM-only
code does not need `--enable-preview`.

```bash
javac --release 26 Main.java
java --enable-native-access=ALL-UNNAMED Main
java --enable-native-access=my.module Main
```

More selective is better:
put FFM code on module path and enable only that module.

Control illegal native access:

```bash
java --illegal-native-access=allow|warn|deny ...
```

JDK 24+ default is `warn`; future default expected `deny`.

## jextract

Use `jextract` when binding nontrivial headers.

Obtain:
<https://jdk.java.net/jextract/>\
Source:
<https://github.com/openjdk/jextract>

Typical:

```bash
jextract \
  -l :/absolute/path/to/libfoo.so \
  --output gensrc \
  -I /path/to/include \
  -t com.example.foo \
  /path/to/foo.h

javac -sourcepath gensrc Main.java
java -cp gensrc:. --enable-native-access=ALL-UNNAMED Main
```

Generated bindings handle many descriptors, layouts, and upcall helpers.
Still review ownership/lifetime/error conventions.

## Common bugs

- Missing `--enable-native-access` → warning or `IllegalCallerException`
  depending config/JDK.
- `invokeExact` args not exact carriers (`int` vs `long`, missing return cast) →
  `WrongMethodTypeException`.
- Using Linux/x64 layout assumptions on Windows/x64 (`long` mismatch).
- Mapping `size_t`/`strlen` to `JAVA_INT` on Linux/x64; use `JAVA_LONG` or
  canonical `size_t` layout.
- Guessing C macro constants such as `sysconf` names; use `jextract` or
  generated constants.
- Accessing arena segment after close → `IllegalStateException`.
- Passing confined-arena segment to another thread → failure.
- Dereferencing zero-length pointer without sizing →
  `IndexOutOfBoundsException`.
- Confusing pointer out-parameter slots (`T**`) with returned handles (`T*`) →
  native `SQLITE_MISUSE`, crashes, or corrupted state.
- Wrong `FunctionDescriptor` → unspecified behavior/JVM crash.
- Forgetting C struct padding/alignment → corrupted fields/native call failure.
- Upcall stub arena closes while native code still stores callback pointer →
  dangling function pointer.
- Native code stores pointer to arena buffer after arena close → dangling
  pointer.
- Treating unsigned values as signed without conversion.
- Assuming Java memory model guarantees for native memory; normal JMM guarantees
  do not apply to off-heap native segments.
- Using `Arena.global()` or `ofAuto()` where deterministic cleanup required.

## Decision guide

- Small C function, simple signature → hand-code downcall.
- Many functions/structs/macros/header churn → `jextract`.
- Native returns allocated pointer → wrap with `reinterpret(size, arena,
  cleanup)` and matching free.
- Native writes into caller buffer → allocate in arena, pass segment, read after
  call.
- Native stores callback/buffer past call → allocate with lifetime covering
  native use; define explicit close/unregister.
- Cross-thread native access → shared arena or copy data to heap.
