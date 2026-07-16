---
name: fxdriver
description: "Drive and inspect JavaFX apps through fxdriver JSON-RPC. Use for JavaFX UI automation, assertions, screenshots, and debugging."
license: MIT
compatibility: "fxdriver @project.version@. Requires JDK @maven.compiler.release@+."
---

# fxdriver

Drive JavaFX UI only; native dialogs and OS/global input need another tool.

## Loop

1. Start with `launch` or attach to a PID.
2. Call `capabilities`, then `snapshot --summary`; use full `snapshot` only when needed.
3. Prefer `nodeId`, then `textExact`, `eid`, role, or text. `selectorPath` is diagnostic, never a selector.
4. Act with the control-specific method: `fire`/`click`, `setText`/`type`, `setValue`, `selectIndex`, `fireMenuItem`, `tableCell`, or `key`.
5. `wait`/`assert` the result. Capture and read screenshots for visual claims.
6. Save `events` on failure; call `shutdown` and terminate launched apps.

## Launch lifecycle

`launch` supervises the app and blocks until it exits. Run it as a long-lived background process, redirect stdout/stderr, and wait for its first JSON line. Do not timeout or kill the launcher while driving the app: its shutdown hook kills the app.

```sh
java -jar @fxdriver.skill.jar@ launch --quiet -- java [options] app.Main > target/fxdriver-launch.json 2> target/fxdriver-launch.err &
```

The JSON line contains `pid`, `port`, and `token`. Pass the token as the final CLI argument or `FXDRIVER_TOKEN`.

## Essential rules

- Input payloads use `value`; `text` selects a node.
- `key` emits synthetic JavaFX events, not OS keystrokes.
- Screenshot paths resolve in the app working directory; use absolute paths only when it differs from your shell.
- `ok:true` means the primitive ran, not that intended state changed—verify afterward.
- Add semantic JavaFX ids only with permission.
- Parse JSON with `jq` or Node, not Python.
- Trust observable UI; do not decompile app/JAR bytecode unless driver evidence cannot answer.
- Video and modal-safe asynchronous dispatch are unavailable.

Read `references/protocol.md` only when exact JSON is needed; read `references/troubleshooting.md` only after a failure.
