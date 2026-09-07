---
name: java-pattern-switch
description: "Use when Java 21+ `switch` uses type patterns, `null`, guards, sealed exhaustiveness, or pattern dominance."
license: CC-BY-4.0-summary
compatibility: "JDK 21+"
metadata:
  sources:
    - "https://openjdk.org/jeps/441"
    - "https://openjdk.org/jeps/440"
---

# Pattern Matching for switch

Use for **Pattern Matching for switch** work from [JEP 441](https://openjdk.org/jeps/441) when targeting JDK 21+.

## When active

- Refactor `instanceof`/cast chains into pattern switches.
- Handle `case null` explicitly when needed.
- Use sealed hierarchies and record patterns for exhaustive switches.

## Pitfalls

- Pattern labels are tested in source order; dominated labels are compile-time errors.
- `default` does not match `null`.
- Put narrow cases before broad cases such as `Object`.

## Examples

Read `examples/jep-441.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/441
- https://openjdk.org/jeps/440
