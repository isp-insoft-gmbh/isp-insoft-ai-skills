---
name: java-native-access-restrictions
description: "Use when Java 24+ JNI or FFM code triggers native-access warnings or needs explicit native access flags."
license: CC-BY-4.0-summary
compatibility: "JDK 24+"
metadata:
  sources:
    - "https://openjdk.org/jeps/472"
    - "https://openjdk.org/jeps/454"
---

# Prepare to Restrict JNI

Use for **Prepare to Restrict JNI** work from [JEP 472](https://openjdk.org/jeps/472) when targeting JDK 24+.

## When active

- Add `--enable-native-access=...` for modules/classpath code that loads or links native code.
- Test with `--illegal-native-access=deny`.
- Inventory JNI with `jnativescan` and native library loads with JFR.

## Pitfalls

- `ALL-UNNAMED` grants all classpath code; prefer modules for narrow grants.
- Library code cannot silently grant itself native access.
- FFM and JNI restriction policy are aligned in JDK 24+.

## Examples

Read `examples/jep-472.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/472
- https://openjdk.org/jeps/454
