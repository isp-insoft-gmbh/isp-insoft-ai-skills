---
name: desloppy
description: "Use when cleaning or refactoring existing code to remove accidental complexity, duplication, needless indirection, weak types, or overengineering without changing behavior."
---

# Desloppy

Slop works, but costs too much to read, change, or trust.

## Boundary

- Existing code only.
- Preserve observable behavior, public APIs, error meaning, security boundaries, accessibility, and relevant performance behavior.
- No features, unrelated bug fixes, weakened tests, or scope creep.
- Deletion beats addition.

## Scope

- One scope is one place or one cross-cutting seam.
  Never both.
- State scope in one sentence.
- Cannot enumerate callers, tests, and affected boundaries → shrink scope.

## Work

1. Read scope in full, then callers, tests, public API, and project instructions.
2. Read only references triggered by code:
   - `references/deep-nesting.md` → flatten control flow
   - `references/long-functions.md` → separate jobs only when separation helps
   - `references/single-use-functions.md` → remove single-use indirection
   - `references/magic-values.md` → expose hidden meaning or relationships
   - `references/missing-types-docs.md` → sharpen types; remove noisy comments
   - `references/poor-error-handling.md` → preserve useful error information
   - `references/overengineered-error-handling.md` → remove excess machinery
   - `references/legacy-overengineering.md` → verify compatibility claims
   - `references/complexity.md` → disentangle dependencies
   - `references/duplication.md` → unify knowledge that must change together
   - `references/poor-patterns.md` → remove unearned ceremony
3. Run smallest project-defined gates covering scope.
   Keep pre-existing failures visible.
4. Choose highest-impact behavior-preserving cleanup inside scope.
5. Existing tests do not prove preserved behavior → add focused proof before refactoring.
   Never change test expectations merely to make refactor pass.
6. Make smallest surgical change.
   Second scope appears → leave it.
7. Run relevant project-defined gates again.

## Authority

- Project instructions, source, tests, and project-defined gates outrank this skill.
- `references/*.md` provide local, conditional guidance.
- No other skill required.
