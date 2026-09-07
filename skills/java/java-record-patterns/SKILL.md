---
name: java-record-patterns
description: "Use when Java 21+ code deconstructs records with `instanceof` or `switch`, especially nested record patterns."
license: CC-BY-4.0-summary
compatibility: "JDK 21+"
metadata:
  sources:
    - "https://openjdk.org/jeps/440"
    - "https://openjdk.org/jeps/441"
---

# Record Patterns

Use for **Record Patterns** work from [JEP 440](https://openjdk.org/jeps/440) when targeting JDK 21+.

## When active

- Replace `instanceof R r` plus accessor calls with `R(...)` record patterns.
- Use nested record patterns for record graphs.
- Use `var` component patterns when explicit component types add noise.

## Pitfalls

- Record patterns match records only and do not match `null`.
- Every record component position needs a pattern.
- Generic record patterns infer type arguments; ordinary type patterns do not.

## Examples

Read `examples/jep-440.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/440
- https://openjdk.org/jeps/441
