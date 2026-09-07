---
name: java-sequenced-collections
description: "Use when Java 21+ code needs ordered collection APIs: first/last elements, reverse views, or encounter-order maps/sets."
license: CC-BY-4.0-summary
compatibility: "JDK 21+"
metadata:
  sources:
    - "https://openjdk.org/jeps/431"
---

# Sequenced Collections

Use for **Sequenced Collections** work from [JEP 431](https://openjdk.org/jeps/431) when targeting JDK 21+.

## When active

- Accept `SequencedCollection` instead of `List`/`Deque` when only encounter order matters.
- Use `SequencedSet` and `SequencedMap` for ordered set/map views and first/last entries.
- Use `reversed()` for a reverse-ordered view, not a copy.

## Pitfalls

- Empty first/last operations throw `NoSuchElementException`.
- Some implementations throw `UnsupportedOperationException` for mutating first/last operations.
- Use `sequencedKeySet()`/`sequencedValues()`/`sequencedEntrySet()` for map views.

## Examples

Read `examples/jep-431.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/431
