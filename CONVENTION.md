# Skill convention

The contract every skill in this repo follows. One contract spans the whole
complexity map — from a lone `SKILL.md` to a JavaFX driver with a Maven build.

## Definitions

- **skill**: a directory containing `SKILL.md` and optional support files. It
  may live directly under `skills/` or inside a natural family such as
  `skills/java/java-ffm/`.
- **install unit**: a top-level `skills/<unit>/` directory. Installation,
  ownership, status, and removal always operate on this complete directory.
- **family**: an install unit containing multiple nested skills. Family members
  remain independently discoverable at runtime but cannot be installed
  separately.
- **complex unit**: an install unit with `mise.toml`, a build-specific source
  layout, and one or more generated `dist/<name>/` skills. `fxdriver` and
  `writing-skills` are complex units.
- **artifact**: the complete directory copied for an install unit. This is its
  source directory for Markdown-only units and its built output for complex
  units.
- **harness**: an agent runtime that discovers skills in a directory
  (`claude`, `pi`, `codex`).
- **target**: any caller-selected skills directory used instead of a known
  harness path.

## Natural source hierarchy

Markdown skills stay where their subject taxonomy belongs:

```text
skills/
├── experimentation/
│   └── SKILL.md
├── java/
│   ├── java-ffm/
│   │   └── SKILL.md
│   └── java-virtual-threads/
│       └── SKILL.md
└── fxdriver/                  # complex exception
    ├── mise.toml
    ├── src/main/...
    └── dist/...
```

The runner recursively discovers `SKILL.md` while skipping `.git`, `dist`,
`node_modules`, and `target`. A Markdown-only unit needs no `mise.toml` and no
build. A standalone unit copies its skill directory directly; a family copies
its complete hierarchy to `<destination>/<unit>/`.

## Complex build contract

A complex unit owns its toolchain and tasks in `skills/<unit>/mise.toml`.
Its `[tasks.build]` emits `./dist/<name>/` containing a final `SKILL.md` for
every produced skill. A standalone complex unit installs `dist/<unit>/`
directly. A complex family installs the complete `dist/` hierarchy under
`<destination>/<unit>/`. Root tasks fan out through mise's monorepo paths, so
each complex unit receives its own tools.

```sh
mise run //skills/fxdriver:build # build one complex owner
mise run build                   # build every complex owner
```

On Windows, run native `//skills/...` task paths in PowerShell or cmd because
Git Bash mangles the leading `//`.

Shared helpers:

- `scripts/sh.mjs <cmd…>` runs commands and normalizes Unix wrappers on Windows.
- `scripts/pack.mjs [--clean-root] [--from <dir>] [--name <name>]` creates a
  complex unit's installable `dist/<name>/` output.
- `scripts/check.mjs` validates one complex skill artifact.
- `scripts/check-frontmatter.mjs --source` recursively validates every source
  skill.

`writing-skills` bundles its pinned YAML and Markdown parsers into one checker
file. Its artifact contains neither `package.json` nor `node_modules`, so the
installed checker needs no package installation or network access.

`dist/` is gitignored and reproducible from source.

## No manifest

An install unit carries **no metadata file**. Its install identity is its
top-level directory name; nested directories containing `SKILL.md` define the
runtime skills in that unit. A unit's `mise.toml` marks build ownership. There
is no `skill.json` declaring kind / capability / harness targets / dependencies,
because:

- **targets** — skills are assumed harness-agnostic (markdown is universal; a
  Java agent is JVM-bound, not harness-bound). The destination is chosen at
  install time with `--harness` or `--target`, not declared per skill.
- **depends**: families provide structural cohesion without dependency closure.
- **runsCode / kind / summary**: decorative. `runsCode` was a non-blocking
  install warning that informs nobody in a self-authored repo; the canonical
  one-liner already lives in `SKILL.md` frontmatter `description`.

If a real cross-unit dependency, a harness-incompatible skill, or third-party
distribution appears, reintroduce a minimal manifest then. Until then, the
install unit is the top-level directory; `mise.toml` only marks a complex build.

## Install / uninstall

`install` does **clean-then-copy** for complete top-level units. A standalone
unit lands at `<destination>/<unit>/`; a family lands there with all member
skills nested beneath it. Use `--harness <name>` for a known harness or
`--target <dir>` for any skills directory; the options are mutually exclusive.
Harness paths default to `~/.claude/skills`, `~/.pi/agent/skills`, and
`~/.codex/skills`; override them with `SKILLS_HARNESS_<NAME>`.
`uninstall` remains harness-based and removes the complete unit.

### The install ledger — ownership memory

A destination dir is shared: other tools and hand-written skills may live there
too. To avoid destroying one and to track our own installs across source
deletions, the runner keeps a ledger at **`<destination>/.isp-skills.json`** —
`{ unit: artifactHash }`
for every unit **we** installed. It lives at the destination root, outside unit
directories, so status hashing is unaffected. This is install-time runtime
state, distinct from skill source: byte-hashing can answer "is this current?",
but never "did we put it here?".

It closes two holes a nameless install can't:

- **Foreign collision.** Installing `<unit>` when a directory of that name
  exists, is **not** in the ledger, and differs from ours causes `install` to
  **refuse** rather than clobber it. `--force` takes it over and records it. A
  pre-existing copy byte-identical to ours is adopted into the ledger without
  copying.
- **Orphans.** Delete a unit from `skills/` and `status` flags its ledger entry
  as **`ORPHAN`**. `uninstall --orphans` removes those installs and their ledger
  entries.

`uninstall` is symmetric: it removes only unit directories in our ledger and
**refuses** a same-named directory we did not install unless `--force` is used.

`list`, `status`, `install`, and `uninstall` are implemented by the dependency-free
`scripts/skills.mjs`. Build, verify, format, and lint remain mise task entrypoints.

## Format & lint

Two root tools cover every skill's common file types, configured once:

- **dprint** (`dprint.json`) — formats markdown (+frontmatter), json, toml.
- **biome** (`biome.json`) — formats **and** lints js/mjs.

`mise run fmt` writes; `mise run lint` is read-only and fails on errors (style
nits like compact ternaries are downgraded to warnings — surfaced, not blocking).
Both respect gitignored paths. Biome excludes fxdriver because its Java sources
belong to the Maven toolchain; dprint still handles its Markdown and configuration.

**Complex skills extend.** A complex owner may declare `[tasks.fmt]` or
`[tasks.lint]` in its `mise.toml`; the root tasks fan out only to owners defining
those tasks. fxdriver uses Spotless for Java while root tooling handles common
repository formats.

## mise's role

mise pins repository tools and complex skills' runtimes, owns complex
build/verify/fmt/lint tasks, and is the task entrypoint. Pin every executable
invoked by tasks or tests in the nearest applicable `[tools]`; tool upgrades
must preserve unrelated tools. mise does **not** resolve a complex skill's own
dependency graph; that stays with its package manager.

## Adding a skill

1. Put a standalone Markdown skill at `skills/<unit>/SKILL.md`, or put related
   skills beneath one family directory such as `skills/java/<name>/SKILL.md`.
   Frontmatter `name` must equal the skill directory name.
2. For a complex unit, add `mise.toml`, build and verify tasks, and reproducible
   `dist/<name>/` outputs.
3. Install the top-level unit with `mise run install -- <unit> --harness <h>` or
   `mise run install -- <unit> --target <dir>`.
