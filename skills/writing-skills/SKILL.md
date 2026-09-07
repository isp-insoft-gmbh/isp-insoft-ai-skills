---
name: writing-skills
description: "Use when creating, reviewing, refactoring, or packaging agent skills: SKILL.md files, skill descriptions, references, scripts, assets, discovery, activation, and validation."
---

# Writing Skills

Skills are progressive disclosure: descriptions select behavior;
bodies load on demand.
Keep durable principles here, not copied API documentation.

## Preflight

Before writing, identify:

- Outcome, intended trigger, authority, non-goals, and observable success.
- Existing skills/prompts/extensions to reuse;
  inspect related policies for contradictions.
- Asset: prompt for a short expansion;
  skill for conditional guidance plus references/helpers;
  extension only for runtime integration.
- Invocation: automatic matching or manual only.
  Check installed Pi docs for `disable-model-invocation` and `/skill:name`;
  do not add a redundant dispatcher prompt.
- Placement: global for personal cross-project behavior;
  project for repository-specific work;
  package for behavior owned by that package.
- Sources: installed docs/help first, then official version-appropriate docs.
  Separate durable facts from facts the agent must look up each time.
- Research scratch: an isolated OS temp directory outside repositories.
  Persist research elsewhere only when explicitly requested.
- Validation scenario and expected behavior before authoring.
  Review requests produce findings, not edits;
  obtain approval before implementation.

## RED → GREEN → REFACTOR

1. **RED:** run the harmless scenario without the target skill before creating it.
   For edits, also capture current behavior when needed to reproduce the defect.
   Record the actual gap; if baseline passes, do not invent failure
   or add redundant guidance.
2. **GREEN:** write the minimum skill that closes the observed gap.
   Keep one clear trigger, ordered steps, boundaries,
   and source lookup rules in `SKILL.md`.
   Move optional details into `references/`
   and deterministic work into `scripts/`.
3. **REFACTOR:** rerun the same scenario with the skill,
   using the same model, settings, and tools.
   Inspect skill loading and observable behavior,
   not merely the agent's claim of success.
   Test a nearby non-trigger and relevant permission/error boundaries.
   Prune duplication and rerun affected checks.

Use fresh non-interactive Pi runs with `--no-session`
and an explicit tool allowlist.
Disable unrelated extensions, prompts, context files, and skills
for the controlled comparison; enable only the target with `--skill` for GREEN.
Read-only tools are the default;
mutation tests may touch isolated temporary fixtures only.
These flags restrict the test setup, not an OS sandbox;
inspect target instructions and helpers before running them.
Use an interactive-terminal workflow only when interaction or terminal UI is under test.
Keep scenario, acceptance criteria, command, model/settings, outputs,
and verdict in temporary evidence.
Report blocked checks and residual gaps explicitly;
never manufacture RED or substitute a static pass for behavioral evidence.

## Authoring rules

- Split broad workflows into independently useful, single-outcome skills;
  grouping directories need no `SKILL.md`.
- Express composition as natural-language outcomes;
  never name, path, or mandate loading another skill.
- `name`: 1-64 lowercase letters/digits, single internal hyphens;
  match the directory for portability.
- `description`: non-empty, at most 1024 characters;
  describe when to load, not the entire workflow.
- Use valid YAML quoting or block scalars for punctuation;
  hidden/manual skills still need a description.
- Resolve references/scripts/assets relative to the skill directory,
  not the caller's cwd.
- Default helpers to cross-platform Node `.mjs`;
  use another runtime only for a domain requirement.
- Scripted skills include `package.json`, `npm test` invoking `node --test`,
  and isolated `.test.mjs` fixtures.
- Write failing helper regression tests first;
  do not execute unreviewed target helpers during an audit.
- Keep runtime secrets, downloaded research,
  and generated evidence out of the skill package.

## Mechanical checks

Run `node scripts/check.mjs <skill-dir>`;
see [checker scope](references/checker.md).
Run target tests and repository checks separately.
Finish with scope, RED/GREEN evidence, checks, and gaps.
