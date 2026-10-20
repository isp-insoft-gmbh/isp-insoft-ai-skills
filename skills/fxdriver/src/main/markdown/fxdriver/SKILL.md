---
name: fxdriver
description: "Drive and inspect JavaFX apps through fxdriver JSON-RPC. Use for JavaFX UI automation, assertions, screenshots, and debugging."
license: MIT
compatibility: "fxdriver @project.version@. Requires JDK 26+ and JavaFX 26+."
---

# fxdriver

Drive JavaFX UI only; native dialogs and OS/global input need another tool.
Resolve bundled `fxdriver.jar` relative to this skill and pass its absolute path; no driver checkout is required.
Use standard shell/HTTP facilities.

## Runtime

Require JDK 26+ for driver and app; require JavaFX 26+ in the app.
Complete [runtime preflight](references/runtime.md): resolve the installed JAR and real Java executables before cwd/environment isolation.
Reuse the app's launch configuration rather than assembling another JavaFX runtime.
For test-owned headless launches, add `-Dglass.platform=Headless -Dprism.order=sw -Djava.awt.headless=true` to the app JVM.
Use headed mode when visible desktop interaction is required; preserve existing attached sessions.
Headless Glass does not make native dialogs or AWT desktop features automatable.
Verify semantic actions, readback and screenshots in the selected backend.
Do not add pre-26 compatibility paths.

## Loop

1. Start with `launch` or attach to a PID.
2. Verify `version.agentSha256` against the selected JAR, then inspect `capabilities` and `snapshot --summary`.
3. Discover by stable ID or role/name/text; use fresh observed `eid` for precise active-view targeting.
   Missing/duplicate IDs are normal; `selectorPath` is diagnostic, never a selector.
4. Choose typed control operations, converter-aware `setValue`, separate editor `commit`, bounded model reads or scoped `key`.
   Accessibility roles nominate candidates, not proven support or successful outcomes.
5. `wait`/`assert` actual values, selection or application state; layout pulses and dispatch counts are separate evidence.
   Capture and read screenshots for visual claims.
6. Preserve redacted failure evidence; shut down only test-owned instances and verify process exit.

## Launch lifecycle

`launch` supervises the app and blocks until it exits.
Use a persistent process facility, capture stdout/stderr in private OS temp, and save its driver-owned endpoint JSON.
Do not timeout or kill the launcher while driving the app: its shutdown hook kills the app.
The following argv templates use the absolute paths established by runtime preflight.

```text
<driver-java> -jar <driver-jar> launch --quiet -- <app-java> [options] <main-or-jar> [app-args]
<driver-java> -jar <driver-jar> rpc <endpoint-json> capabilities '{}'
```

Reuse the saved endpoint JSON directly for RPC; no manual port/token parsing.

## Essential rules

- Input payloads use `value`; `text` selects a node.
- `key` emits synthetic JavaFX events, not OS keystrokes.
- Use absolute screenshot paths; omission allocates private OS-temp output, not app-cwd files.
- `ok:true` is not app completion; reconcile pending `actionId`, then verify semantic state.
- `cancelling`/`unknown` means unfinished setup/work; never blindly repeat mutations.
- Mark actual secret-value controls; use `sensitive:true` for secret-bearing requests and assertions.
- Use explicit replace/extend selection and bounded list/visible-leaf-column reads.
- Logical eligibility is not physical clickability or OS-composited screenshot evidence.
- Add semantic JavaFX ids only with permission.
- Parse JSON with `jq` or Node, not Python.
- Trust observable UI; application-source improvements require permission, not app/JAR decompilation.
- Use only advertised capabilities; `pending` is not completion and uncertain mutations must not be blindly retried.
- Keep temporary launch state and evidence in OS temp; use absolute screenshot paths.

Read all of `references/protocol.md` in one call only when exact JSON is needed; read `references/troubleshooting.md` only after a failure.
