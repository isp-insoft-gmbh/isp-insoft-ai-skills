---
name: java-scoped-values
description: "Use when Java 25+ code passes immutable contextual data through call chains or child threads, especially instead of `ThreadLocal`."
license: CC-BY-4.0-summary
compatibility: "JDK 25+"
metadata:
  sources:
    - "https://openjdk.org/jeps/506"
    - "https://openjdk.org/jeps/444"
---

# Scoped Values

Use for **Scoped Values** work from [JEP 506](https://openjdk.org/jeps/506) when targeting JDK 25+.

## When active

- Declare `private static final ScopedValue<T>`.
- Bind with `ScopedValue.where(KEY, value).run(...)` or `.call(...)`.
- Use for request/user/tenant/trace context that callees read but do not mutate.

## Pitfalls

- `get()` outside the binding scope fails.
- Do not use for mutable bidirectional state.
- Keep scoped value fields private to preserve access control.

## Examples

Read `examples/jep-506.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/506
- https://openjdk.org/jeps/444
