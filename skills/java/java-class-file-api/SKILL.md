---
name: java-class-file-api
description: "Use when Java 24+ code parses, generates, or transforms `.class` files with `java.lang.classfile`."
license: CC-BY-4.0-summary
compatibility: "JDK 24+"
metadata:
  sources:
    - "https://openjdk.org/jeps/484"
---

# Class-File API

Use for **Class-File API** work from [JEP 484](https://openjdk.org/jeps/484) when targeting JDK 24+.

## When active

- Use `ClassFile.of()` as the parse/build/transform entry point.
- Use models and builders instead of visitor boilerplate.
- Use transforms to pass through, drop, or replace class-file elements.

## Pitfalls

- This is not reflection over loaded classes.
- Do not manually manage constant pools, labels, or stack maps unless necessary.
- Final API differs from preview names.

## Examples

Read `examples/jep-484.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/484
