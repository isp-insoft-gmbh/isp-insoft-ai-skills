---
name: java-unsafe-memory-migration
description: "Use when code calls `sun.misc.Unsafe` memory-access methods and should migrate to `VarHandle` or FFM APIs."
license: CC-BY-4.0-summary
compatibility: "JDK 23+"
metadata:
  sources:
    - "https://openjdk.org/jeps/471"
    - "https://openjdk.org/jeps/498"
    - "https://openjdk.org/jeps/454"
---

# Deprecate Unsafe Memory-Access Methods

Use for **Deprecate Unsafe Memory-Access Methods** work from [JEP 471](https://openjdk.org/jeps/471) when targeting JDK 23+.

## When active

- Replace on-heap field/array access with `VarHandle`.
- Replace off-heap allocation/copy/fill with `MemorySegment`/`Arena`.
- Run with `--sun-misc-unsafe-memory-access=debug|deny` to find offenders.

## Pitfalls

- Reflective Unsafe calls still count.
- Do not migrate to other JDK internals.
- [JEP 498](https://openjdk.org/jeps/498) warning behavior is a migration signal, not a fix.

## Examples

Read `examples/jep-471.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/471
- https://openjdk.org/jeps/498
- https://openjdk.org/jeps/454
