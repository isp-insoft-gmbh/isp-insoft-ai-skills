# isp-insoft-ai-skills

Shared skills for Claude Code, Pi, and Codex.
Pick what you need.
Keep it updated from here.

## Install skills

Install [Mise](https://mise.jdx.dev/getting-started.html), clone this repo, then open a terminal in the repo root.

```nu
mise install
mise sk
```

Pick a destination.
For project-local skills, enter your agent's skills directory, not the project root.
Pick skill numbers, separated by commas, then confirm.

That's it for Markdown skills.
If a skill reports **not built**, [build it](#build-skills-that-need-it), then try again.

## Update your skills

From this repo:

```nu
git pull --ff-only
mise sk
```

Pick the same destination.
Existing inventory → update what you installed before.
Recent project paths show up in the picker.

Using compiled skills?
[Rebuild them](#build-skills-that-need-it) after pulling, before updating.

Local edits stay untouched.
Skills removed from this repo show up with their name and description; you decide whether to delete them.
Default: keep.

Check the final summary.
A failed exit does not mean nothing happened: other skills may have updated successfully.
No rollback.

## Add more skills

```nu
mise sk add --pick
```

Pick the destination, then the skills to add.
Unselected skills stay installed.

For a project without the destination picker:

```nu
mise sk add --pick --target ../my-project/.agents/skills
```

Use the skills directory your agent reads.

Already know what you want?

```nu
mise sk add java security
```

## Find skills

```nu
mise sk list
```

Names and descriptions.
Source files live in [skills/](skills/).

Related skills install as a family.
`java` installs all `java-*` members; no need to pick them one by one.

## Check global installations

```nu
mise sk status
```

Read-only report for known global harness directories.
For just one harness:

```nu
mise sk status --harness pi
```

## Update all global installations

```nu
mise sk update
```

Checks Claude, Pi, and Codex global locations.
Updates only recorded skills.
Does not search your disk for projects.

For a project without the picker:

```nu
mise sk update --target ../my-project/.claude/skills
```

## Replace local edits with the shared version

**This discards local edits. No backup.**
Make sure changes you want to keep are already in this repo.

```nu
mise sk update --harness pi --force
```

Or use `--target <project-skills-dir>` instead of `--harness pi`.
Force requires an explicit destination; it cannot update every harness at once.
In a terminal, confirm before anything changes.
Without a terminal, `--force` counts as consent and the warning is still printed.
Only inventoried, installed skills are eligible; orphan deletion still needs its own confirmation.

## Remove skills

```nu
mise sk remove java --harness pi
```

Deletes the installed family.
**No extra confirmation.**
Removal currently supports global harnesses, not arbitrary project paths.

Only want to clean up skills removed from this repo?

```nu
mise sk remove --orphans --harness pi
```

Shows each orphan and asks before deleting it.

## Build skills that need it

Markdown skills need no build.
Compiled skills do.
Run the command shown by the installer, for example:

```nu
mise run //skills/fxdriver:build
```

Rebuild after pulling changes, even if old build output is still there.
Or build everything:

```nu
mise run build
```

On Windows, use Nushell, PowerShell, or cmd for `//skills/...` paths.
Git Bash can rewrite them.

## Reference

### Commands

`mise sk` = `mise run sk`.
Our repo task, not the builtin `mise skills` command.

```nu
mise sk help
```

| Option                        | Effect                                             |
| ----------------------------- | -------------------------------------------------- |
| `--harness claude\|pi\|codex` | Skip destination picker; use a global harness      |
| `--target <dir>`              | Skip destination picker; use this skills directory |
| `--pick`                      | Pick skills even if an inventory exists            |
| `--all`                       | Explicitly select every install unit               |

Use either `--harness` or `--target`, not both.

### Destinations

| Harness | Global skills directory |
| ------- | ----------------------- |
| Claude  | `~/.claude/skills`      |
| Pi      | `~/.pi/agent/skills`    |
| Codex   | `~/.codex/skills`       |

Override paths with `SKILLS_HARNESS_CLAUDE`, `SKILLS_HARNESS_PI`, or `SKILLS_HARNESS_CODEX`.
The picker shows global harnesses first, then recent projects, then a new project directory option.
Missing recent directories are marked unavailable, not silently recreated.

### Inventory and updates

Each destination has an `.isp-skills.json` inventory: installed top-level units and their installation hashes.
A unit is one skill or a whole family.
Individual family members cannot be selected separately.

- No names, no inventory → skill picker.
- No names, existing inventory → update recorded units.
- Names, `--pick`, or `--all` → add selected units.
- Updates use this checkout; no implicit Git pull or build.
- Missing installed directories → skip, not reinstall.
- `update` skips missing or empty inventories.
- Malformed inventory → fail before updating any destination.

Without forced updates, local edits cause a nonzero exit but do not block updates to other units.
The summary separates updated, unchanged, deleted, preserved conflicts, kept orphans, skipped, and errors.
Conflict and error paths are repeated at the end.
The same summary appears when `add` finds an inventory and runs an update.

Orphan descriptions come from installed files.
Missing or unreadable descriptions are marked unavailable.
Confirming deletion removes the installed files and inventory entry.
Local modifications get a warning before that confirmation.
`status` never deletes anything.

Foreign same-named content is protected.
`--force` lets `add` take it over or `remove` delete it.
`add --force` does not overwrite locally modified inventoried units.
Only `update --force` can deliberately replace those edits and refresh their inventory hashes.

### Running without a terminal

First install needs an explicit destination and skill names or `--all`.
`--pick` needs a terminal.
Inventory-based updates work without one.
Scoped `update --force` warns, then replaces local edits without another prompt.
Orphans stay installed, even with `remove --orphans`.
Named removal still deletes without asking.

### Output and history

Skill names and headings are bold in a terminal.
Picker and orphan descriptions wrap; `list` keeps one width-bounded line per skill and truncates long descriptions.
Redirected output is plain by default.
`NO_COLOR` disables styling; `FORCE_COLOR=1` enables it explicitly.

Successful project installs and explicit project updates remember destination paths, newest first, without duplicates.
Global harness operations leave project history alone.
History stays on your machine, separate from installation inventories:

- Windows: `%LOCALAPPDATA%/isp-insoft-ai-skills/state.json`.
- Linux: `$XDG_STATE_HOME/isp-insoft-ai-skills/state.json`, fallback `~/.local/state/isp-insoft-ai-skills/state.json`.
- macOS: `~/Library/Application Support/isp-insoft-ai-skills/state.json`.

During setup, blank input, `q`, Ctrl+C, or declining confirmation cancels before installation or history changes.
During orphan cleanup, declining keeps that orphan; earlier updates stay applied.

### Working on this repo

See [CONVENTION.md](CONVENTION.md) for adding skills.

| Task              | Purpose                                   |
| ----------------- | ----------------------------------------- |
| `mise run verify` | Validate sources and compiled skills      |
| `mise run test`   | Run helper-script regression tests        |
| `mise run fmt`    | Format sources                            |
| `mise run lint`   | Check formatting and lint without changes |

Compiled skills also have native tasks such as `mise run //skills/fxdriver:verify`.

```text
skills/<unit>/SKILL.md         standalone skill
skills/<unit>/<name>/SKILL.md  family member
skills/<unit>/mise.toml        compiled-unit tasks and toolchain
skills/<unit>/dist/            build output (gitignored)
scripts/                      repo helpers
mise.toml                     runtime pins and task entrypoints
```

## License

[MIT](LICENSE) © ISP-Insoft GmbH
