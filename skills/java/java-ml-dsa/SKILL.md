---
name: java-ml-dsa
description: "Use when Java 24+ crypto needs quantum-resistant ML-DSA signatures via standard JCA APIs."
license: CC-BY-4.0-summary
compatibility: "JDK 24+"
metadata:
  sources:
    - "https://openjdk.org/jeps/497"
---

# ML-DSA

Use for **ML-DSA** work from [JEP 497](https://openjdk.org/jeps/497) when targeting JDK 24+.

## When active

- Use `KeyPairGenerator`, `Signature`, and `KeyFactory` with `ML-DSA`.
- Choose `ML-DSA-44`, `ML-DSA-65`, or `ML-DSA-87`.
- Use parameter-set-specific algorithms when strict matching matters.

## Pitfalls

- ML-DSA is not Dilithium-compatible.
- Integer key-size initialization is invalid.
- JDK 24 does not add ML-DSA for TLS or JAR signing.

## Examples

Read `examples/jep-497.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/497
