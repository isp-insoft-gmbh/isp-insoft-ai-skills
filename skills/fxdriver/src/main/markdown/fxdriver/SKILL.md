---
name: fxdriver
description:
  "Drive JavaFX desktop apps through fxdriver JSON-RPC: attach to a running JVM,
  inspect snapshots, choose selectors, click/type/wait/assert, capture
  screenshots, and debug UI flows. Use when testing or automating JavaFX apps
  with fxdriver."
license: MIT
compatibility:
  "fxdriver @project.version@. Requires JDK @maven.compiler.release@+."
---

# fxdriver

Use fxdriver as a low-level JavaFX driver. It observes public JavaFX UI state
and performs requested actions. You decide test policy, assertions, visual
quality judgments, and app-specific workflows.

## Core loop

1. Ensure target JavaFX app is running.
2. Attach fxdriver to its JVM PID.
3. Snapshot UI.
4. Pick stable selectors (`nodeId`, `textExact`, `type`, `role`, `eid`).
5. Act: `click`/`fire` buttons, `setText`/`type` fields, `select` for
   choice/range/table/tree controls, `fireMenuItem` for menus, and `key` for
   real keystrokes (Enter/Tab/chords).
6. Wait/assert.
7. Capture screenshot when uncertain or after visual changes; use
   `videoStart`/`videoStep`/`videoStop` to record a whole run as GIF or APNG
   with a stable canvas and native step titles.
8. Use events/logs to explain failures.

## Required references

Read these when using this skill:

- `references/protocol.md` — JSON-RPC methods and selector forms.
- `references/workflow.md` — recommended observe/act/wait loop.
- `references/visual.md` — screenshot, image summary, and diff usage.
- `references/troubleshooting.md` — attach/Wayland/selector/debug notes.

Examples:

- `examples/basic-flow.md`
- `examples/duplicate-review-flow.md`

## Runtime

Use the packaged CLI from this skill directory:

```sh
java -jar @fxdriver.skill.jar@
```

fxdriver @project.version@ requires JDK @maven.compiler.release@+. It is
compiled and tested against JavaFX @javafx.version@; target apps provide their
own JavaFX runtime.

## Rules for agents

- Prefer stable selectors: `nodeId`, `textExact`, `eid`, `role`, then text
  contains.
- If important controls have no stable node ids, tell the human. Propose adding
  JavaFX node ids as a low-risk app-under-test change, but never mutate app code
  silently.
- Use `value` for `setText`/`type` payloads. `text` is a selector field, not an
  input payload.
- `click`/`fire` drive `ButtonBase` controls and `ListView`; use `select` for
  choice, picker, range, table, tree, and tree-table controls. Use
  `fireMenuItem` for `MenuBar`, `MenuButton`, `SplitMenuButton`, and context
  menus. For keyboard-only interactions (submit with Enter, navigate with
  Tab/arrows, trigger accelerators), use `key`.
- Do not rely only on JSON assertions for visual quality. Read screenshots when
  layout/colors are under review.
- For whole-flow videos, prefer `canvas:"fixed"` with explicit `width`/`height`,
  absolute output paths, and `titleMode:"band"` plus `videoStep` calls before
  major phases. Use `quality:"high"` when text/color fidelity matters; keep the
  default GIF output when maximum player compatibility matters.
- Do not invent high-level policy checks inside fxdriver. Use primitives
  (`snapshot`, `screenshot`, `image-diff`, semantic state) and reason in the
  agent/test layer.
- If an action changes UI, follow with `wait`/`assert` and usually a `snapshot`
  or `screenshot`.
- Keep artifacts in project-local target/output dirs, not `/tmp`, unless caller
  requests otherwise.
