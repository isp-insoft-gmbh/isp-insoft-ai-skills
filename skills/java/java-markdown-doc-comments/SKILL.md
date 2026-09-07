---
name: java-markdown-doc-comments
description: "Use when writing or reviewing JDK 23+ Javadoc comments with `///` Markdown syntax."
license: CC-BY-4.0-summary
compatibility: "JDK 23+"
metadata:
  sources:
    - "https://openjdk.org/jeps/467"
---

# Markdown Documentation Comments

Use for **Markdown Documentation Comments** work from [JEP 467](https://openjdk.org/jeps/467) when targeting JDK 23+.

## When active

- Use `///` documentation comments with CommonMark.
- Use Markdown links to program elements, e.g. `[List]` or `[text][List]`.
- Keep `@param`, `@return`, and other Javadoc tags when useful; tag bodies use Markdown.

## Pitfalls

- Plain blank lines terminate a `///` doc comment.
- Use HTML tables when captions/accessibility are needed.
- Older Javadoc tools do not understand Markdown comments.

## Examples

Read `examples/jep-467.md` for JEP-derived snippets.

## References

- https://openjdk.org/jeps/467
