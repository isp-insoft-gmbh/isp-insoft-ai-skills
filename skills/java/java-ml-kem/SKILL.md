---
name: java-ml-kem
description: "Use when Java 24+ crypto needs quantum-resistant ML-KEM key encapsulation via standard JCA/JCE APIs."
license: CC-BY-4.0-summary
compatibility: "JDK 24+"
metadata:
  sources:
    - "https://openjdk.org/jeps/496"
    - "https://openjdk.org/jeps/452"
---

# ML-KEM

Use for **ML-KEM** work from [JEP 496](https://openjdk.org/jeps/496) when targeting JDK 24+.

## When active

- Use `KeyPairGenerator`, `KEM`, and `KeyFactory` with `ML-KEM`.
- Choose `ML-KEM-512`, `ML-KEM-768`, or `ML-KEM-1024`.
- Use KEM encapsulation bytes to establish shared secrets.

## Pitfalls

- ML-KEM is not Kyber-compatible.
- Do not use ML-KEM for signatures.
- Java 24 does not imply ML-KEM TLS support.

## Examples

Read `examples/jep-496.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/496
- https://openjdk.org/jeps/452
