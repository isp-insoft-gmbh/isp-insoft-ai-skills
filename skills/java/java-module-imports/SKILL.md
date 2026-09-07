---
name: java-module-imports
description: "Use when Java 25+ source can simplify many package imports with `import module ...;`."
license: CC-BY-4.0-summary
compatibility: "JDK 25+"
metadata:
  sources:
    - "https://openjdk.org/jeps/511"
    - "https://openjdk.org/jeps/512"
---

# Module Import Declarations

Use for **Module Import Declarations** work from [JEP 511](https://openjdk.org/jeps/511) when targeting JDK 25+.

## When active

- Use `import module java.base;` or another module for broad examples/prototypes.
- Use specific imports to resolve ambiguity.
- Useful in compact source files, tutorials, JShell-like code, and examples.

## Pitfalls

- Imports only exported packages from named modules, not classpath/unnamed-module classes.
- Broad module imports can create ambiguous simple names.
- Prefer explicit imports in mature production code when clarity matters.

## Examples

Read `examples/jep-511.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/511
- https://openjdk.org/jeps/512
