---
name: java-stream-gatherers
description: "Use when Java 24+ streams need custom intermediate operations such as windowing, scanning, folding, or bounded concurrent mapping."
license: CC-BY-4.0-summary
compatibility: "JDK 24+"
metadata:
  sources:
    - "https://openjdk.org/jeps/485"
---

# Stream Gatherers

Use for **Stream Gatherers** work from [JEP 485](https://openjdk.org/jeps/485) when targeting JDK 24+.

## When active

- Prefer built-in `Gatherers` before custom gatherers.
- Use `stream.gather(gatherer)` for intermediate transforms.
- Use a combiner only when the gatherer is truly parallel-capable.

## Pitfalls

- Collectors are terminal; gatherers are intermediate and can short-circuit.
- Do not expose mutable internal buffers downstream.
- Order-dependent gatherers often cannot safely run in parallel.

## Examples

Read `examples/jep-485.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/485
