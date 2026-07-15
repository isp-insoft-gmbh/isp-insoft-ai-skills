# isp-insoft-ai-skills

Shared agent skills for the team — one repo, many skills, one convention that
scales from a single `SKILL.md` to a skill with a real build step. Works across
harnesses: Claude Code, pi, codex.

[mise](https://mise.jdx.dev) is the single entry point: it pins each skill's
runtimes and drives every task. Install it, then everything below is `mise run`.

## Quickstart

```sh
mise install                          # fetch runtimes (node, dprint, biome; java for fxdriver)
mise run build                        # build every skill -> skills/<name>/dist/<name>/
mise run install -- --harness claude  # install all skills into the claude harness
```

## Commands

| task                                            | does                                                                   |
| ----------------------------------------------- | ---------------------------------------------------------------------- |
| `mise run build`                                | build every skill into `dist/<name>/`                                  |
| `mise run verify`                               | full per-skill check (tests, frontmatter)                              |
| `mise run test`                                 | regression tests for the repo helper scripts                           |
| `mise run fmt` / `lint`                         | format / lint md·json·toml·js (+ skill-local, e.g. java)               |
| `mise run list`                                 | skills + kind + build state + harnesses + description                  |
| `mise run status -- --harness <h>`              | per skill: `current` · `STALE` · `not-installed` · `ORPHAN`            |
| `mise run install -- [name…] --harness <h>`     | install (all, or named) — refuses to clobber a skill it didn't install |
| `mise run install-all`                          | build, then install all skills into claude, pi, and codex              |
| `mise run uninstall -- <name…> --harness <h>`   | remove skills it installed                                             |
| `mise run uninstall -- --orphans --harness <h>` | remove installs whose source was deleted here                          |

One skill at a time uses the native task path: `mise run //skills/fxdriver:build`
(`verify`, `fmt`, `lint` too). `--force` lets install/uninstall act on a
same-named dir the repo didn't install. On Windows, run these in PowerShell or
cmd — Git Bash mangles the leading `//`.

## Skills

| skill             | kind            | installs to       |
| ----------------- | --------------- | ----------------- |
| `experimentation` | markdown        | claude, pi, codex |
| `isp-start-day`   | markdown + Node | claude, pi, codex |
| `uuid-generator`  | markdown + Node | claude, pi, codex |
| `fxdriver`        | Maven / JavaFX  | claude, pi, codex |

See [CONVENTION.md](CONVENTION.md) for the contract when creating new skills.

## Layout

```
skills/<name>/SKILL.md      skill source
skills/<name>/mise.toml     build/verify tasks + toolchain (marks the dir as a skill)
skills/<name>/dist/<name>/  build output a harness consumes (gitignored)
scripts/                    cross-platform build + install helpers (plain node)
mise.toml                   runtime pins + task entrypoints
```

## License

[MIT](LICENSE) © ISP-Insoft GmbH