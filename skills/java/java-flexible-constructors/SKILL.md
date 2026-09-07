---
name: java-flexible-constructors
description: "Use when Java 25+ constructors need validation, computed arguments, or field initialization before explicit `super(...)`/`this(...)`."
license: CC-BY-4.0-summary
compatibility: "JDK 25+"
metadata:
  sources:
    - "https://openjdk.org/jeps/513"
---

# Flexible Constructor Bodies

Use for **Flexible Constructor Bodies** work from [JEP 513](https://openjdk.org/jeps/513) when targeting JDK 25+.

## When active

- Move argument validation before `super(...)`.
- Compute superclass constructor arguments in locals.
- Assign uninitialized fields declared in the same class before `super(...)` when superclass code may observe them.

## Pitfalls

- No implicit/explicit `this` use in the prologue except allowed same-class field assignments.
- No `super` field/method access before superclass construction.
- Fields with initializers cannot be assigned in the prologue.

## Examples

Read `examples/jep-513.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/513
