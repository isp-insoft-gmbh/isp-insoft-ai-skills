---
name: mise
description: "Use when editing Mise config or diagnosing config discovery, activation, shims, trust, installs, or backends. Not for running documented repository tasks."
---

# Mise

Mise declares project tools, env, and tasks in TOML.
For ordinary task execution, follow repo instructions without loading this skill.

## Model

- Config = system/user/global + project/env files, discovered upward; use `mise config ls`, `-E`.
- Shared config: `mise.toml`; legacy tools: `.tool-versions`; local/secrets: local config.
- `[tools]` requests versions via backends; `[env]` changes env/PATH; `[tasks]` and task files run in mise context.
- `activate` mutates shells; `exec`/`x` wraps one command; shims wrap executables.
- Trust gates env/code effects; inspect directives, hooks, templates, sourced files, plugins/backends.

## Workflow

1. Read repo conventions and inspect config directives without exposing secret values.
   Distinguish the agent's tool shell from the user's interactive shell and the configured task shell.
2. Ask local CLI help first: `mise --help`, `mise <cmd> --help`.
   Use `--no-config` for config-free probes; these cannot establish project-effective state.
   After inspecting config effects and trust, select the relevant `doctor`, `config ls`, `ls`, or `tasks` diagnostic.
3. Fetch/cite docs for exact syntax, installs, backends, flags, or changed behavior.
4. Inspect task structure with `mise tasks ls`, `mise tasks info <task>`, and `mise tasks validate`.
   `mise run --dry-run <task>` previews execution, not a sandbox; config evaluation and output still need review.
   `MISE_AUTO_INSTALL=0`, `--skip-tools`, and `--no-deps` do not make task execution read-only.
   Isolate `MISE_DATA_DIR`/`MISE_CACHE_DIR` for temporary probes; local paths do not prevent other side effects.
5. For activation warnings, identify all loaded activation files and their generators before editing.
   Fix the owning generator or confirmed duplicate, not the generated symptom; use `nushell` for `.nu` syntax and `linux-userland` for Linux HOME/XDG boundaries.
6. Treat config writes, installs/removals, activation-file writes, bootstrap, and cleanup as mutations even with local scope.
   Make the smallest authorized change, validate config/task structure, and verify affected behavior.

## Environment safety

- Never dump `mise env`, `mise env --json`, dotenv files, or the full environment.
- Inspect only named variables needed for diagnosis; redact before tool output reaches the transcript.
  Prefer presence, equality, or resolution checks over values; names alone do not make values safe.

## Trust

- `mise trust --show` inspects state.
- Never trust real config, trust all, or set trusted paths without explicit approval.
- If blocked, name exact file + risky features; ask before trusting it.
- Auto-trust only isolated temp configs you created.

## More

- [Sources](references/sources.md).
- [Examples](examples/patterns.md).
