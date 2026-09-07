---
name: java-kem-api
description: "Use when Java 21+ crypto uses `javax.crypto.KEM` encapsulators/decapsulators for shared-secret establishment."
license: CC-BY-4.0-summary
compatibility: "JDK 21+"
metadata:
  sources:
    - "https://openjdk.org/jeps/452"
---

# Key Encapsulation Mechanism API

Use for **Key Encapsulation Mechanism API** work from [JEP 452](https://openjdk.org/jeps/452) when targeting JDK 21+.

## When active

- Use KEM for key encapsulation rather than forcing it into `Cipher` or `KeyAgreement`.
- Sender encapsulates with receiver public key; receiver decapsulates with private key.
- Transmit encapsulation bytes and algorithm params when needed.

## Pitfalls

- KEM key-pair generation still uses `KeyPairGenerator`.
- Provider selection can depend on key and parameters.
- Do not assume `SecretKey.getEncoded()` is available.

## Examples

Read `examples/jep-452.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/452
