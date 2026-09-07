# isp-insoft-ai-skills

Shared agent skills for the team — one repo, many skills, one convention that
scales from a single `SKILL.md` to a skill with a real build step. Works across
harnesses: Claude Code, pi, codex.

[mise](https://mise.jdx.dev) is the single entry point: it pins each skill's
runtimes and drives every task. Install it, then everything below is `mise run`.

## Quickstart

```sh
mise install                          # fetch runtimes (node, dprint, biome; java for fxdriver)
mise run build                        # build complex skills; markdown skills need no build
mise run install -- --harness claude  # install all skills into the claude harness
```

## Commands

| task                                            | does                                                          |
| ----------------------------------------------- | ------------------------------------------------------------- |
| `mise run build`                                | build complex skills; markdown-only skills need no build      |
| `mise run verify`                               | validate all sources and run complex-skill checks             |
| `mise run test`                                 | regression tests for the repo helper scripts                  |
| `mise run fmt` / `lint`                         | format / lint common files plus complex-skill checks          |
| `mise run list`                                 | one width-bounded line per skill: bold name + description     |
| `mise run status -- --harness <h>`              | per unit: `current` · `STALE` · `not-installed` · `ORPHAN`    |
| `mise run install -- [unit…] --harness <h>`     | install all or named units into a known harness               |
| `mise run install -- [unit…] --target <dir>`    | install all or named units into an arbitrary skills directory |
| `mise run install-all`                          | build, then install all units into claude, pi, and codex      |
| `mise run uninstall -- <unit…> --harness <h>`   | remove units it installed                                     |
| `mise run uninstall -- --orphans --harness <h>` | remove installs whose source was deleted here                 |

A complex skill uses its native task path: `mise run //skills/fxdriver:build`
(`verify`, `fmt`, `lint` too). Markdown-only skills have no local tasks.

Installation operates on top-level units. A single-skill unit installs that
skill directly. A family installs as one directory with every member nested
inside it. For example, `mise run install -- java --harness pi` installs the
complete `java` family under `~/.pi/agent/skills/java/`; individual `java-*`
members cannot be selected separately.

`--harness` and `--target` are mutually exclusive.
Both install modes retain collision protection and ownership tracking.
`--force` lets install/uninstall act on a same-named unit the repo didn't install.
On Windows, run native task paths in PowerShell or cmd because Git Bash mangles
the leading `//`.

## Skills

### Engineering workflow

- `coding-guidelines`
- `desloppy`
- `experimentation`
- `mise`
- `security`
- `vertical-slices`
- `writing-skills`

### Java and JavaFX

- `fxdriver` and its `fxdriver-instructions` companion
- `java-class-file-api`
- `java-ffm`
- `java-final-field-mutation`
- `java-flexible-constructors`
- `java-http3-client`
- `java-kdf-api`
- `java-kem-api`
- `java-markdown-doc-comments`
- `java-ml-dsa`
- `java-ml-kem`
- `java-module-imports`
- `java-native-access-restrictions`
- `java-pattern-switch`
- `java-record-patterns`
- `java-scoped-values`
- `java-security-manager-disabled`
- `java-sequenced-collections`
- `java-source-scripts`
- `java-stream-gatherers`
- `java-unnamed-variables-patterns`
- `java-unsafe-memory-migration`
- `java-virtual-threads`

Each entry remains an independently discoverable skill at runtime. Installation
keeps related entries together in their top-level family.
See [CONVENTION.md](CONVENTION.md) for the contract when creating new skills.

## Layout

```
skills/<unit>/<name>/SKILL.md  markdown family member at its natural path
skills/<unit>/SKILL.md         standalone markdown skill
skills/fxdriver/mise.toml      complex family tasks and toolchain
skills/fxdriver/src/           complex family source layout
skills/fxdriver/dist/<name>/   built family members (gitignored)
scripts/                       cross-platform repository helpers
mise.toml                      runtime pins + task entrypoints
```

## License

[MIT](LICENSE) © ISP-Insoft GmbH
