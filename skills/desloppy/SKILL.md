---
name: desloppy
description: "Use when the user asks to desloppy, clean up, simplify, refactor, or review a specific file, directory, diff, or code scope for messy/sloppy code, code smells, technical debt, duplication, complexity, or overengineering."
---

# Find ugliest slop in code and fix it

## Trigger points

Use for broad cleanup/refactor reviews of existing code when the user wants the
agent to find high-impact slop.

Do not use for normal feature work, exact bug fixes, formatting-only changes, or
cases where the user already specified the exact change to make.

## Instructions

1. **Scan** - Find the ugliest code
2. **Identify** - What makes it slop?
   (nesting, long functions, magic numbers, no types)
3. **Propose** - Pick 1-3 slops with highest impact
4. **Approve** - Report minimal and terse first; wait for approval
5. **Fix** - Rewrite cleanly, with project code style
6. **Verify** - Run tests after fixes and create regression tests beforehand, to
   make sure to not break old behavior

Focus on ugly code. Read the relevant reference before proposing fixes:

- `references/deep-nesting.md` — deep nesting → flatten
- `references/long-functions.md` — long functions → analyze before extracting
- `references/single-use-functions.md` — single use functions → inline
- `references/magic-values.md` — magic values → name them or express relationships
- `references/missing-types-docs.md` — missing types/docs → add them
- `references/poor-error-handling.md` — poor error handling → fix it
- `references/overengineered-error-handling.md` — overengineered error
  handling → simplify
- `references/legacy-overengineering.md` — "legacy" or "backwards
  compatibility" → verify the need
- `references/complexity.md` — complexity → disentangle dependencies
- `references/duplication.md` — duplication → remove only when changes should
  stay together
- `references/poor-patterns.md` — poor patterns → challenge mechanical Clean
  Code / OOP ceremony

Add confidence level to each proposed slop fix:

- **SAFE** - unlikely to introduce breaking changes
- **RISKY** - likely to introduce breaking changes

No mercy.
