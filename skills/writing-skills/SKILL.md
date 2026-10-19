---
name: writing-skills
description: "Use when creating, reviewing, refactoring, or packaging agent skills: SKILL.md files, descriptions, references, scripts, assets, discovery, activation, or validation."
---

# Writing skills

Create one useful, portable skill without duplicating project truth.

## Shape

- Keep `SKILL.md` short, timeless, deduplicated, and bullet-led.
- Write descriptions for routing: concrete capability, trigger phrases, and relevant task context; keep workflow steps in the body.
- Match prescription to risk: flexible guidance for judgement, exact steps for fragile operations.
- Keep durable workflow here; move edge cases, API facts, and examples to [`references/authoring.md`](references/authoring.md).
- Link sources of truth; do not copy mutable documentation.
- Put deterministic, repeated work in dependency-free Node `.mjs` scripts.
- Do not automate semantic judgement, voice, timelessness, or deduplication without observed repeated failure.

## Before

- Define outcome, triggers, authority, non-goals, and observable success.
- Read the selected `SKILL.md` completely; continue truncated reads with `offset`.
- Load references only when relevant; link them directly from `SKILL.md`.
- Inspect existing skills, prompts, extensions, and policy for reuse or conflict.
- Choose the smallest asset: prompt → short expansion; skill → conditional guidance; extension → runtime behavior.
- Verify discovery and `/skill:name` invocation against installed Pi docs; do not infer Pi behavior from Claude Code docs.

## Build

For behavior changes:

- **RED:** run the harmless scenario without the skill; record the actual gap.
- **GREEN:** write the minimum skill that closes that gap.
- **REFACTOR:** rerun the same scenario; test a nearby non-trigger and relevant permission/error boundary; remove duplication.

For source-link or documentation-only refreshes, review accuracy and structure without requiring a new behavioral scenario.
Resolve every local path relative to the skill directory.

## Verify

- Run `node scripts/check.mjs <skill-dir>`; packaging rules and checker limits live in [`references/checker.md`](references/checker.md).
- Run existing tests in each scripted skill directory before relying on helpers.
- Inspect loading and observable behavior when changed, not agent claims.
- Keep scenario, settings, outputs, and verdict in OS temp.
- Report scope, executed checks, blocked checks, and residual gaps; include RED/GREEN evidence when behavior changed.

## Sources

- [Authoring edge cases](references/authoring.md)
- [Checker scope](references/checker.md)
- [Sources of truth](references/sources.md)
