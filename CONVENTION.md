# Skill convention

The contract every skill in this repo follows. One contract spans the whole
complexity map — from a lone `SKILL.md` to a JavaFX driver with a Maven build.

## Definitions

- **skill** — a directory `skills/<name>/` with:
  - `mise.toml` — this skill's `build`/`verify` tasks (+ its toolchain). Its
    presence is what marks the dir as a skill; the dir name **is** the skill name.
  - a `SKILL.md` source (or a build that produces one)
- **artifact** — `skills/<name>/dist/<name>/`, a self-contained skill directory
  that always contains a final `SKILL.md`. The only thing a harness consumes.
- **harness** — an agent runtime that discovers skills in a directory
  (`claude`, `pi`, `codex`).

## The one rule: source ≠ artifact

This project never ships `skills/<name>/` directly. It reads
`skills/<name>/dist/<name>/`, produced by `build`. For a markdown-only skill the
two are identical; for a skill with a build step (fxdriver) they differ. Treating
every skill the same way — build, then install the output — is what lets one
convention span both.

So: **always build, then install the build output. Never install source.**

## Each skill owns its build — mise is hierarchical

mise config merges down the directory tree: from a skill dir, the root + skill
`mise.toml` stack and `[tools]` cascade. A skill's own `[tasks.build]` shadows
the root's when you are in its dir. So **each skill defines its own build/verify**
in `skills/<name>/mise.toml`; the root does not configure per-skill steps.

```sh
mise run //skills/fxdriver:build # builds just fxdriver, with java active
mise run build                   # from root: alias for //skills/...:build (every skill)
```

This is a **mise monorepo** (`monorepo_root = true`, `[monorepo].config_roots =
["skills/*"]` in the root `mise.toml`). Each skill's tasks are addressable as
`//skills/<name>:<task>`, and mise **activates that skill's own `[tools]`** (java
for fxdriver) when running one — that is why we route through mise, not a plain
loop. `//skills/...:<task>` fans out to every skill that defines `<task>`. The
recursive wildcard token is `...`, **not** `*`. The root `build`/`verify`/`fmt`/
`lint` tasks are thin aliases over these paths — no delegator script.

> Per-skill targeting is the native path (`mise run //skills/<name>:build`), not a
> `-- <name>` flag. On Windows, type these in PowerShell/cmd — Git Bash mangles
> the leading `//`. mise task bodies already run via `cmd`, so root aliases are
> unaffected.

## Build contract

Each skill's `mise.toml` `[tasks.build]` must emit `./dist/<name>/` containing a
final `SKILL.md`. Two shared helpers keep this cross-platform (mise runs task
bodies via `cmd /c` on Windows):

- `scripts/sh.mjs <cmd…>` — run a command, normalizing unix wrappers
  (`./mvnw` → `.\mvnw.cmd` on Windows). Use for build steps like Maven.
- `scripts/pack.mjs [--from <dir>]` — copy the deliverable into `./dist/<name>/`.
  No `--from`: prune-copy the skill source (drops `mise.toml`, `target/`,
  `node_modules/`, `.git/`, `dist/`). `--from target/x`: copy a built output
  verbatim.
- `scripts/check.mjs` — verify a `SKILL.md` has frontmatter with a `name`
  matching the dir and a `description`. The default `verify` for skills with no
  build step.

By complexity:

| skill           | `[tasks.build]`                                                  | `[tasks.verify]`       |
| --------------- | ---------------------------------------------------------------- | ---------------------- |
| experimentation | `pack.mjs`                                                       | `check.mjs`            |
| isp-start-day   | `pack.mjs`                                                       | `check.mjs`            |
| fxdriver        | `sh.mjs ./mvnw … package` then `pack.mjs --from target/fxdriver` | `sh.mjs ./mvnw verify` |

`dist/` is gitignored — always reproducible from source.

## No manifest

A skill carries **no metadata file**. Its identity is the directory: the name is
the dir name, and a `mise.toml` marks the dir as a skill. There is no `skill.json`
declaring kind / capability / harness targets / dependencies, because:

- **targets** — skills are assumed harness-agnostic (markdown is universal; a
  Java agent is JVM-bound, not harness-bound). The harness is chosen at install
  time (`--harness`), not declared per skill.
- **depends** — no skill depends on another; the closure machinery was unused.
- **runsCode / kind / summary** — decorative. `runsCode` was a non-blocking
  install warning that informs nobody in a self-authored repo; the canonical
  one-liner already lives in `SKILL.md` frontmatter `description`.

If a real cross-skill dependency, a harness-incompatible skill, or third-party
distribution ever appears, reintroduce a minimal manifest then — the data would
differ anyway. Until then: a skill is a dir with a `mise.toml`, nothing more.

## Install / uninstall

`install` does **clean-then-copy** from `skills/<name>/dist/<name>/` into the
harness dir. `uninstall` removes the installed directory. Harness paths default
to `~/.claude/skills`, `~/.pi/agent/skills`, `~/.codex/skills`; override with
`SKILLS_HARNESS_<NAME>`.

### The install ledger — ownership memory

A harness dir is shared: other tools and hand-written skills live there too. To
avoid destroying one and to track our own installs across source deletions, the
runner keeps a ledger at **`<harness>/.isp-skills.json`** — `{ name: distHash }`
for every skill **we** installed. It lives at the harness root (outside skill
dirs), so per-skill drift hashing is unaffected. This is install-time runtime
state, distinct from skill source — the kind of thing byte-hashing can't supply
(it answers "is this current?", never "did _we_ put it here?").

It closes two holes a nameless install can't:

- **Foreign collision.** Installing `<name>` when a dir of that name exists that
  is **not** in the ledger and differs from ours → `install` **refuses** rather
  than clobber it. `--force` takes it over (and records it). A pre-existing copy
  byte-identical to ours is silently _adopted_ into the ledger (no clobber), so
  upgrading from the pre-ledger era is seamless.
- **Orphans.** Delete a skill from `skills/` and its install is unreachable by
  name. `status` flags any ledger entry with no source as **`ORPHAN`**;
  `uninstall --orphans` removes those installs and their ledger entries (the
  discovery half of uninstall — for when you no longer have the name to type).

`uninstall` is symmetric: it removes only dirs in our ledger and **refuses** a
same-named dir we did not install (`--force` overrides). So neither `install` nor
`uninstall` ever destroys a foreign skill without an explicit `--force`.

`list`, `status`, `install`, `uninstall` are pure node in `scripts/skills.mjs`.
`build`/`verify`/`fmt`/`lint` are native mise monorepo tasks, not node.

## Format & lint

Two root tools cover every skill's common file types, configured once:

- **dprint** (`dprint.json`) — formats markdown (+frontmatter), json, toml.
- **biome** (`biome.json`) — formats **and** lints js/mjs.

`mise run fmt` writes; `mise run lint` is read-only and fails on errors (style
nits like compact ternaries are downgraded to warnings — surfaced, not blocking).
Both skip the self-owned `skills/fxdriver/` subtree and gitignored paths.

**Skills extend.** A skill that has files the root tools don't cover declares its
own `[tasks.fmt]` / `[tasks.lint]` in its `mise.toml`; the root `fmt`/`lint` then
fans out via `//skills/...:fmt`, which runs only in skills that define it.
fxdriver does this for Java (spotless) — root handles its markdown/js, fxdriver
owns its Java formatting. Same hierarchy as build/verify.

## mise's role

mise pins **runtimes** per skill (node, java), **owns each skill's
build/verify/fmt/lint tasks**, and is the task entrypoint. It does **not** resolve a skill's own
dependency graph — that stays with the skill's package manager (Maven). A
teammate who only wants the markdown/script skills never needs a JDK.

## Adding a skill

1. `mkdir skills/<name>`, write `SKILL.md` (frontmatter `name` must equal `<name>`).
2. Write `skills/<name>/mise.toml` with `[tasks.build]` (emit `./dist/<name>/`)
   and `[tasks.verify]`. Copy the nearest existing skill by complexity. Add
   `[tools]` if it needs a runtime to build. This file marks the dir as a skill.
3. `mise run //skills/<name>:build` then `mise run install -- <name> --harness <h>`.
