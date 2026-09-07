---
name: java-kdf-api
description: "Use when Java 25+ crypto derives keys or bytes with `javax.crypto.KDF`, especially HKDF."
license: CC-BY-4.0-summary
compatibility: "JDK 25+"
metadata:
  sources:
    - "https://openjdk.org/jeps/510"
    - "https://openjdk.org/jeps/452"
---

# Key Derivation Function API

Use for **Key Derivation Function API** work from [JEP 510](https://openjdk.org/jeps/510) when targeting JDK 25+.

## When active

- Use `KDF.getInstance("HKDF-SHA256")` for HKDF.
- Use `HKDFParameterSpec` extract/expand specs.
- Use `deriveKey` for `SecretKey` output or `deriveData` for byte output.

## Pitfalls

- PBKDF2 remains under `SecretKeyFactory`; [JEP 510](https://openjdk.org/jeps/510) does not move it to KDF.
- Do not use `KeyGenerator` for deterministic KDF derivation.
- Provider-specific KDFs need their own `AlgorithmParameterSpec`.

## Examples

Read `examples/jep-510.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/510
- https://openjdk.org/jeps/452
