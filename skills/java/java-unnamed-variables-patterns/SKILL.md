---
name: java-unnamed-variables-patterns
description: "Use when Java 22+ code has intentionally unused locals, lambda/catch parameters, resources, or pattern components."
license: CC-BY-4.0-summary
compatibility: "JDK 22+"
metadata:
  sources:
    - "https://openjdk.org/jeps/456"
---

# Unnamed Variables & Patterns

Use for **Unnamed Variables & Patterns** work from [JEP 456](https://openjdk.org/jeps/456) when targeting JDK 22+.

## When active

- Replace intentionally unused variables with `_`.
- Use `_` in nested record patterns to ignore components.
- Use multi-pattern switch labels only when no pattern variables are declared.

## Pitfalls

- `_` is not readable or writable.
- Unnamed method parameters and fields are not supported.
- Bare `_` is valid as an unnamed pattern only inside record patterns.

## Examples

Read `examples/jep-456.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/456
