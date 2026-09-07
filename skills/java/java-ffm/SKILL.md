---
name: java-ffm
description: "Use for Java 26 Foreign Function & Memory API (FFM, Project Panama): java.lang.foreign, native library calls, off-heap memory, MemorySegment, Arena, Linker, SymbolLookup, FunctionDescriptor, MemoryLayout, upcalls/downcalls, jextract, errno, restricted native access."
license: CC-BY-4.0-summary
compatibility: "Java SE 26 / JDK 26; java.lang.foreign stable since Java 22"
metadata:
  sources:
    - "https://openjdk.org/jeps/454"
    - "https://openjdk.org/jeps/472"
    - "https://docs.oracle.com/en/java/javase/26/core/foreign-function-and-memory-api.html"
    - "https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/foreign/package-summary.html"
---

# Java 26 FFM Skill

Use this skill when writing/reviewing Java code using the Foreign Function &
Memory API.

When active:

1. Identify C ABI/platform, exact native signatures, library loading path,
   ownership/lifetime, thread access, and error convention.
2. Prefer safe FFM idioms:
   arena-bound allocation, explicit layouts, exact method handle types, explicit
   cleanup, minimal restricted API use.
3. Read `references/java-26-ffm.md` for distilled API facts, patterns, and
   pitfalls.
4. If implementing bindings for many C declarations, recommend `jextract`;
   hand-code only small bindings.

## Core model

- `MemorySegment` = contiguous memory view:
  address + byte size + scope + access checks.
- `Arena` = native memory lifetime owner.
  Close arena → all segments allocated by it invalid.
- `MemoryLayout` / `ValueLayout` / `AddressLayout` = data shape, size,
  alignment, endianness, access handles.
- `Linker` + `SymbolLookup` + `FunctionDescriptor` = turn native symbol +
  signature into `MethodHandle` downcall.
- Upcall = native code calls Java via `linker.upcallStub(methodHandle,
  descriptor, arena)`.

## Default imports

```java
import java.lang.foreign.*;
import java.lang.invoke.*;

import static java.lang.foreign.ValueLayout.*;
import static java.lang.foreign.MemoryLayout.*;
import static java.lang.foreign.MemoryLayout.PathElement.*;
```

## Native access

Restricted FFM methods need native access enabled.
FFM is final in Java 26; do not add `--enable-preview` for FFM-only code.

```bash
javac --release 26 Main.java
java --enable-native-access=ALL-UNNAMED Main
java --enable-native-access=my.module Main
```

In JDK 24+, illegal native access defaults to warning; future JDK may deny.
For CI, prefer explicit `--enable-native-access=<module>` and consider
`--illegal-native-access=deny`.

## Fast checklists

Before downcall:

- Exact C prototype?
  Include typedef expansion, pointer depth, varargs specialization.
- Correct platform layouts?
  `long` differs:
  Linux/x64 `long` = 64-bit; Windows/x64 `long` = 32-bit.
  `size_t` is `JAVA_LONG` on Linux/x64, not `JAVA_INT`.
- C constants/macros?
  Do not guess magic numbers; use `jextract`, generate constants, or choose APIs
  without macro constants.
- Ownership?
  Who allocates/frees returned pointers?
  Which arena owns passed buffers/upcall stubs?
- Threading?
  Confined arena only owner thread.
  Shared arena for cross-thread access.
- Error path?
  Return sentinel?
  `errno`?
  output parameter?

Before structs:

- Include explicit padding where C ABI inserts it.
- Verify `byteSize()` and `byteAlignment()` vs C `sizeof`/`alignof` if possible.
- Use named layouts + layout path var handles; avoid manual offset math.

Before pointer dereference/out params:

- Pointer from native/read memory is often zero-length.
- C `T** out` means allocate an `ADDRESS` slot, pass that slot, then read the
  real `T*` with `slot.get(ADDRESS, 0)`; use the real handle for later calls,
  not the address of the slot.
  Common example:
  `sqlite3_open(..., sqlite3**)`.
- Attach bounds via `AddressLayout.withTargetLayout(...)` when statically known,
  or `segment.reinterpret(size, arena, cleanup)` when dynamically known.
- Treat `reinterpret` and target layouts as unsafe/restricted:
  wrong size/lifetime can crash JVM.

## Canonical minimal downcall

```java
static final Linker LINKER = Linker.nativeLinker();
static final SymbolLookup LIBC = LINKER.defaultLookup();

static final MethodHandle strlen = LINKER.downcallHandle(
    LIBC.findOrThrow("strlen"),
    FunctionDescriptor.of(JAVA_LONG, ADDRESS) // Linux/x64 size_t, char*
);

static long strlen(String s) throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment cString = arena.allocateFrom(s); // UTF-8, NUL-terminated
        return (long) strlen.invokeExact(cString);
    }
}
```

## Examples

Read `examples/jep-454.md` for JEP-derived snippets.

## Primary references

- [JEP 454](https://openjdk.org/jeps/454): Foreign Function & Memory API
- [JEP 472](https://openjdk.org/jeps/472): Prepare to Restrict the Use of JNI

Open `references/java-26-ffm.md` for:

- API map
- arena/segment rules
- type mapping
- downcall/upcall/variadic/errno patterns
- pointer-return handling
- struct layouts
- jextract workflow
- safety pitfalls
