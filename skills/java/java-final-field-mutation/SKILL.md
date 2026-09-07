---
name: java-final-field-mutation
description: "Use when Java 26+ code mutates `final` fields with deep reflection, serialization frameworks, JNI, or Unsafe."
license: CC-BY-4.0-summary
compatibility: "JDK 26+"
metadata:
  sources:
    - "https://openjdk.org/jeps/500"
---

# Prepare to Make Final Mean Final

Use for **Prepare to Make Final Mean Final** work from [JEP 500](https://openjdk.org/jeps/500) when targeting JDK 26+.

## When active

- Find `Field.setAccessible(true)` plus `Field.set(...)` on final fields.
- Prefer constructors, factories, builders, records, or supported serialization paths.
- Use `--illegal-final-field-mutation=debug|deny` to locate and harden offenders.

## Pitfalls

- `--add-opens` alone does not enable final-field mutation.
- Warnings are per mutating module by default.
- Startup opt-in belongs to the application owner, not a hidden library requirement.

## Examples

Read `examples/jep-500.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/500
